"""Bounded 7z member stream; no uncompressed Posts.xml on disk.

The consumer must exhaust the stream to verify the archive CRC. Producer errors
are propagated at EOF, before the selection phase can be committed complete.
"""
import io
import queue
import threading
from contextlib import contextmanager


@contextmanager
def member_stream(path, member):
    import py7zr
    from py7zr.io import Py7zIO, WriterFactory
    chunks = queue.Queue(maxsize=8)
    stopped = threading.Event()
    failure = []
    finished = object()

    def send(value):
        while not stopped.is_set():
            try:
                chunks.put(value, timeout=.1)
                return
            except queue.Full:
                pass
        raise InterruptedError('Archive consumer stopped')

    class Sink(Py7zIO):
        position = 0
        def write(self, data):
            for offset in range(0, len(data), 65536):
                send(bytes(memoryview(data)[offset:offset + 65536]))
            self.position += len(data)
            return len(data)
        def size(self): return self.position
        def seekable(self): return False
        def seek(self, *args): raise io.UnsupportedOperation('Sequential archive output')
        def read(self, *args): raise io.UnsupportedOperation('Write-only archive sink')
        def flush(self): pass

    class Factory(WriterFactory):
        def create(self, filename):
            if filename != member:
                raise ValueError('Unexpected archive member: ' + filename)
            return Sink()

    def produce():
        try:
            with py7zr.SevenZipFile(path, 'r') as archive:
                if member not in archive.getnames():
                    raise ValueError('Missing archive member: ' + member)
                archive.extract(targets=[member], factory=Factory())
        except BaseException as error:
            failure.append(error)
        finally:
            if not stopped.is_set():
                try: send(finished)
                except InterruptedError: pass

    class Reader(io.RawIOBase):
        remainder = b''
        ended = False
        def readable(self): return True
        def readinto(self, buffer):
            if self.ended: return 0
            while not self.remainder:
                item = chunks.get()
                if item is finished:
                    self.ended = True
                    if failure: raise failure[0]
                    return 0
                self.remainder = item
            count = min(len(buffer), len(self.remainder))
            buffer[:count] = self.remainder[:count]
            self.remainder = self.remainder[count:]
            return count

    thread = threading.Thread(target=produce, name='bounded-7z-stream', daemon=True)
    thread.start()
    try:
        with io.BufferedReader(Reader(), buffer_size=65536) as stream:
            yield stream
    finally:
        stopped.set()
        thread.join(timeout=30)
        if thread.is_alive():
            raise RuntimeError('Archive decompressor did not stop')
