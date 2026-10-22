#include <jni.h>
#include "runtime_init.h"
#include <atomic>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Cancellation {
    JavaVM * vm=nullptr;
    jobject supplier;
    jmethodID get;
    std::atomic<bool> stopped{false},failed{false};
    Cancellation(JNIEnv * env,jobject value) {
        if(!value)throw std::runtime_error("Abbruchprüfung fehlt.");
        env->GetJavaVM(&vm);
        supplier=env->NewGlobalRef(value);
        jclass type=env->FindClass("java/util/function/BooleanSupplier");
        get=env->GetMethodID(type,"getAsBoolean","()Z");
        env->DeleteLocalRef(type);
        if(!supplier || !get)throw std::runtime_error("Abbruchprüfung nicht verfügbar.");
    }
    ~Cancellation() {
        JNIEnv * env=nullptr;
        if(vm->GetEnv(reinterpret_cast<void **>(&env),JNI_VERSION_1_6)==JNI_OK)env->DeleteGlobalRef(supplier);
    }
    bool check() {
        if(stopped)return true;
        JNIEnv * env=nullptr;
        bool attached=vm->GetEnv(reinterpret_cast<void **>(&env),JNI_VERSION_1_6)==JNI_EDETACHED;
        if(attached) {
#ifdef __ANDROID__
            int status=vm->AttachCurrentThread(&env,nullptr);
#else
            int status=vm->AttachCurrentThread(reinterpret_cast<void **>(&env),nullptr);
#endif
            if(status!=JNI_OK){failed=true;stopped=true;return true;}
        }
        bool stop=env->CallBooleanMethod(supplier,get);
        if(env->ExceptionCheck()){env->ExceptionClear();failed=true;stop=true;}
        if(stop)stopped=true;
        if(attached)vm->DetachCurrentThread();
        return stopped;
    }
};
bool abort_embedding(void * data){return static_cast<Cancellation *>(data)->check();}
bool load_progress(float,void * data){return !abort_embedding(data);}
std::string bytes(JNIEnv * env,jbyteArray input) {
    if(!input)throw std::runtime_error("Embedding-Eingabe fehlt.");
    std::string out(env->GetArrayLength(input),'\0');
    env->GetByteArrayRegion(input,0,static_cast<jsize>(out.size()),reinterpret_cast<jbyte *>(out.data()));
    if(env->ExceptionCheck())throw std::runtime_error("Embedding-Eingabe nicht lesbar.");
    return out;
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_de_bonsai_offline_EmbeddingGemma2Encoder_00024Native_encode(
        JNIEnv * env,jclass,jbyteArray path,jbyteArray prompt,jint threads,jobject supplier) {
    try {
        Cancellation cancel(env,supplier);
        auto check=[&] {
            if(cancel.check()) {
                const char * type=cancel.failed?"java/lang/IllegalStateException":"java/util/concurrent/CancellationException";
                env->ThrowNew(env->FindClass(type),cancel.failed?"Abbruchprüfung fehlgeschlagen.":"Abgebrochen.");
                throw std::runtime_error("Abgebrochen.");
            }
        };
        check();
        if(threads<1 || threads>8)throw std::runtime_error("Ungültige Encoder-Threadzahl.");
        auto filename=bytes(env,path),text=bytes(env,prompt);
        if(filename.find('\0')!=std::string::npos || text.empty() || text.size()>4096)
            throw std::runtime_error("Ungültige Embedding-Eingabe.");
        bonsai_runtime_init();
        auto mp=llama_model_default_params();
        ggml_backend_dev_t devices[]={nullptr};
        mp.devices=devices;mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback=load_progress;mp.progress_callback_user_data=&cancel;
        using Model=std::unique_ptr<llama_model,decltype(&llama_model_free)>;
        Model model(llama_model_load_from_file(filename.c_str(),mp),llama_model_free);
        check();
        if(!model)throw std::runtime_error("EmbeddingGemma-2-GGUF konnte nicht geladen werden.");
        char architecture[64]={};
        llama_model_meta_val_str(model.get(),"general.architecture",architecture,sizeof(architecture));
        if(std::string(architecture)!="gemma-embedding2" || llama_model_n_embd_out(model.get())!=768)
            throw std::runtime_error("Query-Encoder benötigt gemma-embedding2 mit 768 Dimensionen.");
        const auto * vocab=llama_model_get_vocab(model.get());
        // Match HF tokenization, including BOS/EOS and vocabulary special tokens.
        int count=llama_tokenize(vocab,text.data(),static_cast<int>(text.size()),nullptr,0,true,true);
        if(count>=0 || count < -1024)throw std::runtime_error("Query überschreitet das Encoder-Tokenlimit (1024).");
        std::vector<llama_token> tokens(-count);
        count=llama_tokenize(vocab,text.data(),static_cast<int>(text.size()),tokens.data(),static_cast<int>(tokens.size()),true,true);
        if(count<=0)throw std::runtime_error("Embedding-Tokenisierung fehlgeschlagen.");
        tokens.resize(count);
        auto cp=llama_context_default_params();
        cp.n_ctx=1024;cp.n_batch=1024;cp.n_ubatch=1024;cp.n_seq_max=1;
        cp.n_threads=threads;cp.n_threads_batch=threads;
        cp.embeddings=true;cp.pooling_type=LLAMA_POOLING_TYPE_MEAN;
        cp.attention_type=LLAMA_ATTENTION_TYPE_NON_CAUSAL;
        // This model exceeds FP16 range. Keep attention intermediates in F32.
        cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.type_k=GGML_TYPE_F32;cp.type_v=GGML_TYPE_F32;
        cp.offload_kqv=false;cp.op_offload=false;
        cp.abort_callback=abort_embedding;cp.abort_callback_data=&cancel;
        using Context=std::unique_ptr<llama_context,decltype(&llama_free)>;
        Context context(llama_init_from_model(model.get(),cp),llama_free);
        check();
        if(!context)throw std::runtime_error("Embedding-Kontext konnte nicht erstellt werden.");
        // Noncausal attention requires the entire query in a single physical batch.
        auto batch=llama_batch_get_one(tokens.data(),count);
        int status=llama_decode(context.get(),batch);
        check();
        if(status!=0)throw std::runtime_error("Embedding-Berechnung fehlgeschlagen.");
        const float * output=llama_get_embeddings_seq(context.get(),0);
        if(!output)throw std::runtime_error("Gepooltes Query-Embedding fehlt.");
        for(int i=0;i<768;i++)if(!std::isfinite(output[i]))throw std::runtime_error("Query-Embedding enthält ungültige Werte.");
        auto result=env->NewFloatArray(768);
        if(result)env->SetFloatArrayRegion(result,0,768,output);
        return result;
    } catch(const std::exception & e) {
        if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());
        return nullptr;
    }
}
