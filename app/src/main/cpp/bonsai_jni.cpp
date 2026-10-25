#include <jni.h>
#include <optional>
#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "utf8_stream.h"
#include "reference_cpu.h"
#include "chat_sampler.h"
#include "conversation_state.h"
#include "runtime_init.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

// Process-lifetime handle. Model/context operations use one Java worker; only stop is concurrent.
struct Engine {
    std::atomic<bool> stop{false};
    Model model{nullptr, llama_model_free};
    Context context{nullptr, llama_free};
    common_chat_templates_ptr templates;
    std::string backend = "CPU";
    int context_size = 4096;
    bool gpu = false;
    bool flash_attention=false,kv_q8=false;
    std::vector<llama_token> cached;
    size_t evicted_turns=0;
    std::chrono::system_clock::time_point template_now=std::chrono::system_clock::now();
    size_t cache_resets=0;
    std::string reset_reason="created";
    void clear_cache(const char * reason="explicit") {
        ++cache_resets;reset_reason=reason;
        evicted_turns=0;cached.clear();
        if(context)llama_memory_clear(llama_get_memory(context.get()),true);
    }
    void unload() { ++cache_resets;reset_reason="unload";evicted_turns=0;cached.clear();templates.reset(); context.reset(); model.reset(); }
};
Engine & engine(jlong handle) {
    if (!handle) throw std::runtime_error("Native Engine fehlt.");
    return *reinterpret_cast<Engine *>(handle);
}
bool abort_compute(void * p) { return static_cast<Engine *>(p)->stop.load(); }
bool load_progress(float, void * p) { return !abort_compute(p); }
void fail(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::runtime_error("Leerer Texteingang.");
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Text konnte nicht gelesen werden.");
    return result;
}
jbyteArray result_bytes(JNIEnv * env, const std::string & value) {
    auto out = env->NewByteArray(static_cast<jsize>(value.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return out;
}
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text,bool special=true) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max()))
        throw std::runtime_error("Eingabe ist zu gross.");
    int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, special, true);
    if (count == std::numeric_limits<int32_t>::min()) throw std::runtime_error("Zu viele Tokens.");
    std::vector<llama_token> tokens(static_cast<size_t>(count < 0 ? -count : count));
    count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), special, true);
    if (count < 0) throw std::runtime_error("Tokenisierung fehlgeschlagen.");
    tokens.resize(count);
    return tokens;
}
std::string piece(const llama_vocab * vocab, llama_token token) {
    std::string out(256, '\0');
    int n = llama_token_to_piece(vocab, token, out.data(), static_cast<int32_t>(out.size()), 0, true);
    if (n < 0) {
        out.resize(-n);
        n = llama_token_to_piece(vocab, token, out.data(), static_cast<int32_t>(out.size()), 0, true);
    }
    if (n < 0) throw std::runtime_error("Token konnte nicht dekodiert werden.");
    out.resize(n);
    return out;
}
using DecodeProgress=std::function<void(size_t)>;
void decode_tokens(Engine & e,const llama_token * tokens,size_t count,const DecodeProgress & progress={}) {
    const size_t batch=llama_n_batch(e.context.get());
    for(size_t i=0;i<count;i+=batch) {
        if(e.stop)throw std::runtime_error("Abgebrochen.");
        const size_t n=std::min(batch,count-i);
        auto b=llama_batch_get_one(const_cast<llama_token *>(tokens+i),static_cast<int32_t>(n));
        if(llama_decode(e.context.get(),b)!=0)throw std::runtime_error("Token-Dekodierung fehlgeschlagen (Speicher oder Backend).");
        e.cached.insert(e.cached.end(),tokens+i,tokens+i+n);
        if(progress)progress(i+n);
    }
}
using PrefillProgress=std::function<void(size_t,size_t,double)>;
size_t sync_cache(Engine & e,const std::vector<llama_token> & tokens,bool logits,const PrefillProgress & progress={}) {
    auto keep=conversation_prefix(e.cached,tokens,logits);
    if(keep<e.cached.size()) {
        if(!llama_memory_seq_rm(llama_get_memory(e.context.get()),0,static_cast<llama_pos>(keep),-1)) {
            e.clear_cache("suffix_removal_failed");keep=0;
        } else e.cached.resize(keep);
    }
    const auto start=Clock::now();
    if(progress)progress(keep,tokens.size(),0);
    DecodeProgress decoded;
    if(progress)decoded=[&](size_t count) { const auto ms=elapsed(start);progress(keep+count,tokens.size(),ms>0?count*1000.0/ms:0); };
    decode_tokens(e,tokens.data()+keep,tokens.size()-keep,decoded);
    return keep;
}
PrefillProgress prefill_callback(JNIEnv * env,jobject callback) {
    if(!callback)return {};
    auto type=env->GetObjectClass(callback);auto method=env->GetMethodID(type,"onPrefill","(IID)V");env->DeleteLocalRef(type);
    if(!method)throw std::runtime_error("Prefill-Callback fehlt.");
    return [=](size_t done,size_t total,double rate) {
        env->CallVoidMethod(callback,method,static_cast<jint>(done),static_cast<jint>(total),static_cast<jdouble>(rate));
        if(env->ExceptionCheck())throw std::runtime_error("Prefill-Anzeige unterbrochen.");
    };
}
void stable_input(Engine & e,common_chat_templates_inputs & input) {
    input.enable_thinking=false;
    input.use_jinja=true;
    // Templates using strftime_now must not mutate earlier turns between requests.
    input.now=e.template_now;
}
void retain_window(Engine & e,common_chat_templates_inputs & input) {
    if(!e.evicted_turns)return;
    size_t first=input.messages.size(),turn=0;
    for(size_t i=0;i<input.messages.size();++i) {
        const auto & m=input.messages[i];
        if(m.role=="user" && m.content.rfind("<tool_result>",0)!=0) {
            if(first==input.messages.size())first=i;
            if(turn++==e.evicted_turns) {
                input.messages.erase(input.messages.begin()+first,input.messages.begin()+i);return;
            }
        }
    }
    // A caller supplied a different conversation without an explicit reset.
    e.clear_cache("conversation_window_changed");
}
std::vector<llama_token> fit_prompt(Engine & e,common_chat_templates_inputs & input,size_t reserve,int & dropped) {
    const auto vocab=llama_model_get_vocab(e.model.get());
    for(;;) {
        auto tokens=tokenize(vocab,common_chat_templates_apply(e.templates.get(),input).prompt);
        if(!tokens.empty() && tokens.size()+reserve<=llama_n_ctx(e.context.get()))return tokens;
        // Keep the system prompt and the complete latest user turn (including tool rounds).
        size_t first=input.messages.size(),next=first;
        for(size_t i=0;i<input.messages.size();++i) {
            const auto & m=input.messages[i];
            if(m.role=="user" && m.content.rfind("<tool_result>",0)!=0) {
                if(first==input.messages.size())first=i;
                else { next=i;break; }
            }
        }
        if(next==input.messages.size())throw std::runtime_error("Die aktuelle Nachricht passt mit dem Antwortbudget nicht in den Kontext. Nachricht oder Antwortlimit verkleinern.");
        input.messages.erase(input.messages.begin()+first,input.messages.begin()+next);++dropped;++e.evicted_turns;
    }
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_de_bonsai_offline_NativeEngine_create(JNIEnv * env, jclass) {
    try { return reinterpret_cast<jlong>(new Engine()); }
    catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_resetStop(JNIEnv *, jclass, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->stop.store(false);
}
extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_stop(JNIEnv *, jclass, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->stop.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_unload(JNIEnv * env, jclass, jlong handle) {
    try { engine(handle).unload(); } catch (const std::exception & e) { fail(env, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_clearCache(JNIEnv * env,jclass,jlong handle) {
    try { engine(handle).clear_cache(); } catch(const std::exception & ex) { fail(env,ex); }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_commitTurn(JNIEnv * env,jclass,jlong handle,jobjectArray roles,jobjectArray contents) {
    try {
        auto & e=engine(handle);
        if(!e.context || !e.model)throw std::runtime_error("Kein Kontext geladen.");
        common_chat_templates_inputs input;stable_input(e,input);input.add_generation_prompt=false;
        const auto count=env->GetArrayLength(roles);
        if(count!=env->GetArrayLength(contents))throw std::runtime_error("Ungueltiger Chat.");
        for(jsize i=0;i<count;++i) {
            auto r=static_cast<jbyteArray>(env->GetObjectArrayElement(roles,i));
            auto c=static_cast<jbyteArray>(env->GetObjectArrayElement(contents,i));
            common_chat_msg m;m.role=bytes(env,r);m.content=bytes(env,c);
            if(m.role=="assistant")m.content=without_reasoning(m.content);
            input.messages.push_back(std::move(m));env->DeleteLocalRef(r);env->DeleteLocalRef(c);
        }
        retain_window(e,input);
        auto start=Clock::now();
        int dropped=0;auto tokens=fit_prompt(e,input,0,dropped);auto reused=sync_cache(e,tokens,false);
        return result_bytes(env,common_json({{"cached_tokens",e.cached.size()},
            {"kv_positions",llama_memory_seq_pos_max(llama_get_memory(e.context.get()),0)+1},
            {"commit_decoded_tokens",tokens.size()-reused},{"commit_native_ms",elapsed(start)},
            {"cache_resets",e.cache_resets},{"cache_reset_reason",e.reset_reason}}).dump());
    } catch(const std::exception & ex) {
        if(handle)engine(handle).clear_cache("commit_error");
        fail(env,ex);return nullptr;
    }
}

// Warm only the token prefix shared by closed-system and two complete first-turn
// renders. Never cache an assistant header from a system-only generation prompt.
static jbyteArray warm_prefix(JNIEnv * env,jlong handle,jbyteArray system,jobject progress) {
    try {
        auto & e=engine(handle);
        if(!e.context || !e.model)throw std::runtime_error("Kein Kontext geladen.");
        common_chat_templates_inputs input;stable_input(e,input);input.add_generation_prompt=false;
        common_chat_msg message;message.role="system";message.content=bytes(env,system);input.messages.push_back(message);
        auto vocab=llama_model_get_vocab(e.model.get());
        auto prefix=tokenize(vocab,common_chat_templates_apply(e.templates.get(),input).prompt);
        input.add_generation_prompt=true;
        for(const auto * probe : {"A", "Zwei andere Wörter: 🪴"}) {
            auto full=input;common_chat_msg user;user.role="user";user.content=probe;full.messages.push_back(user);
            auto tokens=tokenize(vocab,common_chat_templates_apply(e.templates.get(),full).prompt);
            prefix.resize(conversation_prefix(prefix,tokens,false));
        }
        if(prefix.size()+64>llama_n_ctx(e.context.get()))
            throw std::runtime_error("Systempraefix zu gross zum Vorwaermen.");
        auto start=Clock::now();auto reused=sync_cache(e,prefix,false,prefill_callback(env,progress));
        return result_bytes(env,common_json({{"warmed_tokens",prefix.size()},{"decoded_tokens",prefix.size()-reused},{"warmup_ms",elapsed(start)}}).dump());
    } catch(const std::exception & ex) { if(handle)engine(handle).clear_cache("warmup_error");fail(env,ex);return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_warmPrefix(JNIEnv * env,jclass,jlong handle,jbyteArray system) {
    return warm_prefix(env,handle,system,nullptr);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_warmPrefixProgress(JNIEnv * env,jclass,jlong handle,jbyteArray system,jobject progress) {
    return warm_prefix(env,handle,system,progress);
}

extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_referenceCpu(JNIEnv *, jclass, jboolean enabled) {
    bonsai_reference_cpu = enabled;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_load(JNIEnv * env, jclass, jlong handle, jbyteArray path,
                                        jint context_size, jint threads, jboolean gpu) {
    try {
        auto & e = engine(handle);
        e.unload();
        bonsai_runtime_init();
        if (e.stop) throw std::runtime_error("Abgebrochen.");
        if (context_size < 512 || context_size > 8192 || threads < 1 || threads > 8)
            throw std::runtime_error("Ungueltige Einstellungen.");
        ggml_backend_dev_t devices[] = {nullptr, nullptr};
        if (gpu) {
#if BONSAI_HAS_VULKAN
            std::string available;
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                auto dev = ggml_backend_dev_get(i);
                const auto type = ggml_backend_dev_type(dev);
                const auto reg = ggml_backend_dev_backend_reg(dev);
                const std::string backend_name = ggml_backend_reg_name(reg);
                if (!available.empty()) available += ", ";
                available += backend_name + ": " + ggml_backend_dev_description(dev);
                // Mobile GPUs share system RAM and are reported as IGPU.
                if (backend_name == "Vulkan" &&
                    (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)) {
                    devices[0] = dev;
                    break;
                }
            }
            if (!devices[0]) throw std::runtime_error(
                "Keine Vulkan-GPU vom Treiber bereitgestellt. Erkannte Geraete: " +
                (available.empty() ? std::string("keine") : available) + ". Bitte CPU waehlen.");
#else
            throw std::runtime_error("Diese APK enthaelt den CPU-Unterbau. Vulkan benoetigt einen separaten Build.");
#endif
        }
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = gpu ? 99 : 0;
        mp.devices = devices;  // Empty list on CPU prevents accidental GPU buffer allocations.
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        // Scalar reference checks must bypass repacked kernels.
        mp.use_extra_bufts = !bonsai_reference_cpu.load(std::memory_order_relaxed);
        mp.progress_callback = load_progress;
        mp.progress_callback_user_data = &e;
        Model model(llama_model_load_from_file(bytes(env, path).c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error(e.stop ? "Abgebrochen." :
            "Modell konnte nicht geladen werden. GGUF-Datei, freien RAM und Runtime-Version pruefen.");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size;
        cp.n_batch = 512;
        cp.n_ubatch = gpu || bonsai_reference_cpu.load(std::memory_order_relaxed) ? 1 : 512;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        cp.offload_kqv = gpu;
        cp.op_offload = gpu;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.no_perf = false;
        cp.abort_callback = abort_compute;
        cp.abort_callback_data = &e;
        Context context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Kontext konnte nicht angelegt werden. Kleineren Kontext oder CPU versuchen.");
        auto templates = common_chat_templates_init(model.get(), "");
        if (!templates) throw std::runtime_error("Chat-Template fehlt im Modell.");
        if (e.stop) throw std::runtime_error("Abgebrochen.");
        common_json metadata;
        for (const auto key : {"general.architecture", "general.name", "general.file_type", "prism.hadamard.version"}) {
            char buffer[512] = {};
            if (llama_model_meta_val_str(model.get(), key, buffer, sizeof(buffer)) >= 0) metadata[key] = buffer;
        }
        e.flash_attention=false;e.kv_q8=false;
        e.context_size = context_size;
        e.gpu = gpu;
        e.backend = gpu ? std::string("Vulkan - ") + ggml_backend_dev_description(devices[0]) : "CPU";
        e.templates = std::move(templates);
        e.model = std::move(model);
        e.context = std::move(context);
        e.template_now=std::chrono::system_clock::now();
        e.cached.reserve(llama_n_ctx(e.context.get()));
        return result_bytes(env, common_json({{"backend",e.backend},{"context",llama_n_ctx(e.context.get())},{"metadata",metadata},
            {"reference_cpu",bonsai_reference_cpu.load()}}).dump());
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

static void configure_context(JNIEnv * env,jlong handle,jint threads,jint batch_threads,jint micro_batch,bool flash,bool q8) {
    try {
        auto & e = engine(handle);
        if (!e.model) throw std::runtime_error("Kein Modell geladen.");
        if (threads < 1 || threads > 8 || batch_threads < 1 || batch_threads > 8 ||
            micro_batch < 1 || micro_batch > 512 || (micro_batch & (micro_batch - 1)))
            throw std::runtime_error("Ungueltiges Optimierungsprofil.");
        if (e.stop) throw std::runtime_error("Abgebrochen.");
        if(q8 && !flash)throw std::runtime_error("Q8 KV benoetigt Flash Attention.");
        // Thread counts can change without reallocating KV; identical profiles are a no-op.
        if(e.context && e.flash_attention==flash && e.kv_q8==q8 && llama_n_ubatch(e.context.get())==static_cast<uint32_t>(micro_batch)) {
            llama_set_n_threads(e.context.get(),threads,batch_threads);return;
        }
        auto cp = llama_context_default_params();
        cp.n_ctx = e.context_size;
        cp.n_batch = 512;
        cp.n_ubatch = micro_batch;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = batch_threads;
        cp.type_k = q8 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
        cp.type_v = q8 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
        cp.offload_kqv = e.gpu;
        cp.op_offload = e.gpu;
        cp.flash_attn_type = flash ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.no_perf = false;
        cp.abort_callback = abort_compute;
        cp.abort_callback_data = &e;
        e.clear_cache("configure_context");
        e.context.reset(); // Release the previous KV allocation before creating another.
        e.context.reset(llama_init_from_model(e.model.get(), cp));
        e.flash_attention=flash;e.kv_q8=q8;
        if (!e.context) throw std::runtime_error("Optimierungsprofil konnte keinen Kontext anlegen.");
    } catch (const std::exception & e) { fail(env, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_configure(JNIEnv * env,jclass,jlong handle,jint threads,jint batch,jint micro) {
    configure_context(env,handle,threads,batch,micro,false,false);
}
extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_configureAdvanced(JNIEnv * env,jclass,jlong handle,jint threads,jint batch,jint micro,jboolean flash,jboolean q8) {
    configure_context(env,handle,threads,batch,micro,flash,q8);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_probe(JNIEnv * env, jclass, jlong handle, jint steps) {
    try {
        auto & e = engine(handle);
        if (!e.context || !e.model) throw std::runtime_error("Kein Kontext geladen.");
        if (steps < 4 || steps > 128) throw std::runtime_error("Ungueltige Messlaenge.");
        auto vocab = llama_model_get_vocab(e.model.get());
        std::string text;
        for (int i = 0; i < 12; ++i)
            text += "Ein Wald speichert Wasser im Boden. Baeume nutzen Sonnenlicht und bilden neue Blaetter. ";
        auto tokens = tokenize(vocab, text);
        tokens.resize(std::min<size_t>(tokens.size(), 192));
        e.clear_cache("probe_start");
        auto start = Clock::now();
        const size_t batch = llama_n_batch(e.context.get());
        for (size_t i = 0; i < tokens.size(); i += batch) {
            if (e.stop) throw std::runtime_error("Abgebrochen.");
            auto b = llama_batch_get_one(tokens.data() + i, std::min<size_t>(batch, tokens.size() - i));
            if (llama_decode(e.context.get(), b) != 0) throw std::runtime_error("Kernel-Messung: Prefill fehlgeschlagen.");
        }
        const double prefill = elapsed(start);
        start = Clock::now();
        uint64_t fingerprint = 1469598103934665603ULL;
        common_json signature = common_json::array(), top_tokens = common_json::array();
        for (int i = 0; i < steps; ++i) {
            if (e.stop) throw std::runtime_error("Abgebrochen.");
            // Replay identical tokens across profiles so EOS and sampling cannot bias timings.
            auto token = tokens[(i + 7) % tokens.size()];
            auto b = llama_batch_get_one(&token, 1);
            if (llama_decode(e.context.get(), b) != 0) throw std::runtime_error("Kernel-Messung: Decode fehlgeschlagen.");
            const float * logits = llama_get_logits_ith(e.context.get(), -1);
            if (!logits) throw std::runtime_error("Kernel lieferte keine Logits.");
            int best = 0;
            for (int j = 0; j < llama_vocab_n_tokens(vocab); ++j) {
                if (!std::isfinite(logits[j])) throw std::runtime_error("Kernel lieferte ungueltige Zahlen.");
                if (logits[j] > logits[best]) best = j;
            }
            top_tokens.push_back(best);
            for (int j = 0; j < 64; ++j)
                signature.push_back(logits[static_cast<int64_t>(j) * llama_vocab_n_tokens(vocab) / 64]);
            fingerprint = (fingerprint ^ static_cast<uint64_t>(best)) * 1099511628211ULL;
        }
        const double decode = elapsed(start);
        e.clear_cache("probe_end");
        return result_bytes(env, common_json({{"prefill_ms",prefill}, {"decode_ms",decode},
            {"decode_tokens",steps}, {"prompt_tokens",tokens.size()},
            {"tokens_per_second",steps * 1000.0 / std::max(decode, 0.001)},
            {"score_ms",prefill + decode * 128.0 / steps},
            {"micro_batch",llama_n_ubatch(e.context.get())},{"flash_attention_requested",e.flash_attention},{"kv_cache_type",e.kv_q8?"q8_0":"f16"},
            {"fingerprint",std::to_string(fingerprint)}, {"signature",signature}, {"top_tokens",top_tokens}}).dump());
    } catch (const std::exception & ex) { if(handle)engine(handle).clear_cache("probe_error");fail(env,ex);return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_generate(JNIEnv * env, jclass, jlong handle, jobjectArray roles,
                                            jobjectArray contents, jint limit, jint seed, jobject callback, jbyteArray grammar,jint reasoning_budget) {
    try {
        auto & e = engine(handle);
        if (!e.context || !e.model) throw std::runtime_error("Bitte zuerst ein Modell laden.");
        if (limit < 16 || limit > 2048) throw std::runtime_error("Ungueltiges Antwortlimit.");
        if(e.stop) {
            e.clear_cache("stopped");
            return result_bytes(env,common_json({{"prompt_tokens",0},{"output_tokens",0},{"prefill_ms",0},
                {"first_token_ms",0},{"generation_ms",0},{"tokens_per_second",0},{"stopped",true},{"limit_reached",false}}).dump());
        }
        auto count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents) || !count) throw std::runtime_error("Ungueltiger Chat.");
        common_chat_templates_inputs input;
        stable_input(e,input);
        input.add_generation_prompt = true;
        for (jsize i = 0; i < count; ++i) {
            auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            common_chat_msg msg;
            msg.role = bytes(env, r); msg.content = bytes(env, c);
            if(msg.role=="assistant")msg.content=without_reasoning(msg.content);
            env->DeleteLocalRef(r); env->DeleteLocalRef(c);
            input.messages.push_back(std::move(msg));
        }
        const auto grammar_text=grammar?bytes(env,grammar):std::string();
        const int budget=std::clamp(static_cast<int>(reasoning_budget),0,600);
        const int reserve_reasoning=budget;
        const int hard_limit=limit+reserve_reasoning+32;
        retain_window(e,input);
        int dropped=0;
        auto tokens=fit_prompt(e,input,static_cast<size_t>(hard_limit+32),dropped);
        const auto vocab=llama_model_get_vocab(e.model.get());
        auto cb_class = env->GetObjectClass(callback);
        auto on_chunk = env->GetMethodID(cb_class, "onBytes", "([B)V");
        auto on_thinking = env->GetMethodID(cb_class, "onThinking", "([B)V");
        env->DeleteLocalRef(cb_class);
        if (!on_chunk || !on_thinking) throw std::runtime_error("Streaming-Callback fehlt.");
        llama_perf_context_reset(e.context.get());
        const auto cached_before=e.cached.size();
        auto start=Clock::now();
        const auto reused=sync_cache(e,tokens,true,prefill_callback(env,callback));
        double prefill_ms = elapsed(start), first_token_ms = 0;
        auto generation_start = Clock::now(), last_emit = Clock::now();
        Sampler sampler(bonsai_chat_sampler(vocab,static_cast<uint32_t>(seed),grammar_text.c_str()), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Sampler konnte nicht angelegt werden.");
        ReasoningFilter output;
        std::string thinking_pending;
        auto thought_emit=Clock::now();
        auto emit_thinking=[&] {
            const auto count=complete_utf8_prefix(thinking_pending);
            if(!count)return;
            auto data=result_bytes(env,thinking_pending.substr(0,count));
            if(!data)throw std::runtime_error("Kein Speicher fuer die Statuszeile.");
            env->CallVoidMethod(callback,on_thinking,data);env->DeleteLocalRef(data);
            thinking_pending.erase(0,count);thought_emit=Clock::now();
            if(env->ExceptionCheck())throw std::runtime_error("Thinking-Anzeige unterbrochen.");
        };
        output.on_thinking=[&](char c) { thinking_pending+=c;if(thinking_pending.size()>=256 || elapsed(thought_emit)>=80)emit_thinking(); };
        const auto rendered=common_chat_templates_apply(e.templates.get(),input).prompt;
        const auto end=rendered.find_last_not_of(" \t\r\n");
        output.thinking=end!=std::string::npos && end+1>=7 && rendered.compare(end+1-7,7,"<think>")==0;
        size_t emitted=0;
        int generated=0,answer_tokens=0,reasoning_tokens=0,injected=0;
        double first_visible_token_ms=0;
        bool eos=false,reasoning_capped=false;
        auto emit=[&] {
            const auto n=complete_utf8_prefix(output.visible.substr(emitted));
            if(!n)return;
            auto data=result_bytes(env,output.visible.substr(emitted,n));
            if(!data)throw std::runtime_error("Kein Speicher fuer die Antwort.");
            env->CallVoidMethod(callback,on_chunk,data);env->DeleteLocalRef(data);
            emitted+=n;last_emit=Clock::now();
            if(env->ExceptionCheck())throw std::runtime_error("Antwort-Streaming unterbrochen.");
        };
        auto close_reasoning=[&] {
            const std::string closing="</think>";
            const auto prefix=closing.compare(0,output.pending.size(),output.pending)==0?output.pending.size():0;
            auto close=tokenize(vocab,closing.substr(prefix),false);
            for(auto token:close)llama_sampler_accept(sampler.get(),token);
            decode_tokens(e,close.data(),close.size());injected+=static_cast<int>(close.size());
            output.close();reasoning_capped=true;
        };
        if(output.thinking && budget==0)close_reasoning();
        while (answer_tokens < limit && generated < hard_limit && !e.stop) {
            const float * logits = llama_get_logits_ith(e.context.get(), -1);
            if (!logits || !std::all_of(logits, logits + llama_vocab_n_tokens(vocab), [](float x) { return std::isfinite(x); }))
                throw std::runtime_error("Das Modell liefert ungueltige Rechenwerte. CPU-Kompatibilitaetsmodus oder Modelltest verwenden.");
            auto token = llama_sampler_sample(sampler.get(), e.context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { eos = true; break; }
            if (!generated) first_token_ms = elapsed(start);
            const bool thinking=output.thinking;
            const auto visible_before=output.visible.size();
            output.feed(piece(vocab,token));++generated;
            if(first_visible_token_ms==0 && output.visible.size()>visible_before)first_visible_token_ms=elapsed(start);
            if(thinking || output.thinking)++reasoning_tokens;
            else if(output.visible.size()>visible_before)++answer_tokens;
            decode_tokens(e,&token,1);
            if(output.thinking && reasoning_tokens>=budget)close_reasoning();
            if(elapsed(last_emit)>=50)emit();
        }
        output.finish();emit_thinking();emit();
        const double generation_ms = elapsed(generation_start);
        // Remove reasoning now, not only when the next user message arrives.
        auto cleanup_start=Clock::now();
        size_t cleanup_decoded=0;
        if(e.stop)e.clear_cache("stopped");
        else {
            std::string final=output.visible;
            if(!grammar_text.empty() && final.rfind("<final>",0)==0 && final.size()>=15 && final.compare(final.size()-8,8,"</final>")==0)
                final=final.substr(7,final.size()-15);
            common_chat_msg answer;answer.role="assistant";answer.content=final;
            input.messages.push_back(std::move(answer));input.add_generation_prompt=false;
            int cleanup_dropped=0;auto clean=fit_prompt(e,input,0,cleanup_dropped);cleanup_decoded=clean.size()-sync_cache(e,clean,false);
        }
        return result_bytes(env, common_json({{"micro_batch",llama_n_ubatch(e.context.get())},{"flash_attention_requested",e.flash_attention},{"kv_cache_type",e.kv_q8?"q8_0":"f16"},
            {"prompt_tokens",tokens.size()}, {"output_tokens",generated},
            {"prefill_ms",prefill_ms}, {"first_token_ms",first_token_ms},
            {"reused_prompt_tokens",reused},{"decoded_prompt_tokens",tokens.size()-reused},{"dropped_turns",dropped},
            {"first_visible_token_ms",first_visible_token_ms},{"reasoning_tokens",reasoning_tokens},{"reasoning_capped",reasoning_capped},{"injected_tokens",injected},
            {"cache_resets",e.cache_resets},{"cache_reset_reason",e.reset_reason},{"cached_before",cached_before},
            {"cleanup_decoded_tokens",cleanup_decoded},{"cleanup_ms",elapsed(cleanup_start)},{"cached_tokens",e.cached.size()},
            {"kv_positions",llama_memory_seq_pos_max(llama_get_memory(e.context.get()),0)+1},
            {"generation_ms",generation_ms}, {"tokens_per_second", generation_ms > 0 ? generated*1000.0/generation_ms : 0},
            {"stopped",e.stop.load()}, {"limit_reached",!eos && (answer_tokens>=limit || generated>=hard_limit)}}).dump());
    } catch (const std::exception & ex) { if(handle)engine(handle).clear_cache("generate_error");fail(env,ex);return nullptr; }
}
