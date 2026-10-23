"""mmap shard source for IVF, avoiding a second complete Float32 corpus copy."""
from collections import OrderedDict
import hashlib
import json
from pathlib import Path
import struct
import numpy as np


class Field:
    def __init__(self,source,name):self.source,self.name=source,name
    def __getitem__(self,key):
        rows,columns=key if isinstance(key,tuple) else (key,None)
        return self.source.read(self.name,rows,columns)


class ShardFloatSource:
    MAX_READ_ROWS=1048576
    def __init__(self,directory):
        self.directory=Path(directory);self.cache=OrderedDict()
        state=json.loads((self.directory/'export.json').read_text(encoding='utf-8'))
        if not state['complete']:raise ValueError('Finish embedding export before index construction')
        self.identity=state['identity'];self.count=self.identity['count'];self.shards=state['shards']
        self.dimension=self.shards[0]['native_dimension'] if self.count else 768
        self.dtype=np.dtype([('id','<i8'),('vector','<f4',(self.dimension,))])
        if self.count>0xffffffff or self.dimension<256:raise ValueError('Unsupported shard format')
        whole=hashlib.sha256(struct.pack('<8sII',b'BRAGF001',self.dimension,self.count))
        self.offsets=[0];previous=0
        for number,info in enumerate(self.shards):
            if info['number']!=number or info['first_id']<=previous or info['count']<1 or info['native_dimension']!=self.dimension:raise ValueError('Inconsistent shard identity/order')
            path=self.directory/f'shard-{number:06d}.f32'
            if path.stat().st_size!=16+info['count']*self.dtype.itemsize:raise ValueError('Shard size mismatch')
            individual=hashlib.sha256()
            with path.open('rb') as stream:
                header=stream.read(16)
                if header!=struct.pack('<8sII',b'BRAGF001',self.dimension,info['count']):raise ValueError('Shard header mismatch')
                individual.update(header)
                first=stream.read(8);stream.seek(16)
                while block:=stream.read(4*1024**2):individual.update(block);whole.update(block)
                stream.seek(16+(info['count']-1)*self.dtype.itemsize);last=stream.read(8)
            if individual.hexdigest()!=info['f32_sha256']:raise ValueError('Committed shard checksum mismatch')
            if struct.unpack('<q',first)[0]!=info['first_id'] or struct.unpack('<q',last)[0]!=info['last_id']:raise ValueError('Shard boundary IDs mismatch')
            previous=info['last_id'];self.offsets.append(self.offsets[-1]+info['count'])
        if self.offsets[-1]!=self.count:raise ValueError('Incomplete shard row set')
        self.offsets=np.asarray(self.offsets,dtype=np.int64)
        self.reference_sha256=whole.hexdigest()
    def __len__(self):return self.count
    def close(self):
        while self.cache:
            _,value=self.cache.popitem(last=False);value._mmap.close()
    def mapping(self,number):
        if number in self.cache:
            value=self.cache.pop(number);self.cache[number]=value;return value
        if len(self.cache)>=4:
            _,value=self.cache.popitem(last=False);value._mmap.close()
        value=np.memmap(self.directory/f'shard-{number:06d}.f32',dtype=self.dtype,mode='r',offset=16,shape=(self.shards[number]['count'],))
        self.cache[number]=value;return value
    def read(self,name,selector,columns=None):
        scalar=isinstance(selector,(int,np.integer))
        if isinstance(selector,slice):
            start,stop,step=selector.indices(self.count)
            length=len(range(start,stop,step))
            if length>self.MAX_READ_ROWS:raise ValueError('Read in bounded batches, not the complete shard corpus')
            indices=np.arange(start,stop,step,dtype=np.int64)
        else:indices=np.asarray([selector] if scalar else selector,dtype=np.int64)
        if indices.ndim!=1 or len(indices)>self.MAX_READ_ROWS or np.any(indices<0) or np.any(indices>=self.count):raise ValueError('Invalid/unbounded shard selection')
        if name=='vector':
            columns=slice(None) if columns is None else columns
            width=len(np.arange(self.dimension)[columns]);output=np.empty((len(indices),width),dtype='<f4')
        elif name=='id':output=np.empty(len(indices),dtype='<i8')
        else:raise KeyError(name)
        groups=np.searchsorted(self.offsets,indices,side='right')-1
        order=np.argsort(groups,kind='stable');ordered=groups[order]
        boundaries=np.r_[0,np.flatnonzero(ordered[1:]!=ordered[:-1])+1,len(order)]
        for start,stop in zip(boundaries[:-1],boundaries[1:]):
            if start==stop:continue
            number=int(ordered[start]);positions=order[start:stop];local=indices[positions]-self.offsets[number]
            values=self.mapping(number)[name]
            output[positions]=values[local,columns] if name=='vector' else values[local]
        return output[0] if scalar else output
    def __getitem__(self,key):
        if isinstance(key,str):return Field(self,key)
        ids=self.read('id',key);vectors=self.read('vector',key)
        rows=np.empty(np.shape(ids),dtype=self.dtype);rows['id']=ids;rows['vector']=vectors;return rows
