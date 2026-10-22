#include <jni.h>
#include <optional>
#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "utf8_stream.h"
#include "reference_cpu.h"
#include "chat_sampler.h"
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
    void unload() { templates.reset(); context.reset(); model.reset(); }
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
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max()))
        throw std::runtime_error("Eingabe ist zu gross.");
    int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (count == std::numeric_limits<int32_t>::min()) throw std::runtime_error("Zu viele Tokens.");
    std::vector<llama_token> tokens(static_cast<size_t>(count < 0 ? -count : count));
    count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    if (count < 0) throw std::runtime_error("Tokenisierung fehlgeschlagen.");
    tokens.resize(count);
    return tokens;
}
std::string piece(const llama_vocab * vocab, llama_token token) {
    std::string out(256, '\0');
    int n = llama_token_to_piece(vocab, token, out.data(), static_cast<int32_t>(out.size()), 0, false);
    if (n < 0) {
        out.resize(-n);
        n = llama_token_to_piece(vocab, token, out.data(), static_cast<int32_t>(out.size()), 0, false);
    }
    if (n < 0) throw std::runtime_error("Token konnte nicht dekodiert werden.");
    out.resize(n);
    return out;
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
Java_de_bonsai_offline_NativeEngine_referenceCpu(JNIEnv *, jclass, jboolean enabled) {
    bonsai_reference_cpu = enabled;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_load(JNIEnv * env, jclass, jlong handle, jbyteArray path,
                                        jint context_size, jint threads, jboolean gpu) {
    try {
        auto & e = engine(handle);
        e.unload();
        static std::once_flag init;
        std::call_once(init, [] { ggml_backend_load_all(); llama_backend_init(); });
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
        // Repacked tensors bypass the dot-product reference wrappers.
        mp.use_extra_bufts = false;
        mp.progress_callback = load_progress;
        mp.progress_callback_user_data = &e;
        Model model(llama_model_load_from_file(bytes(env, path).c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error(e.stop ? "Abgebrochen." :
            "Modell konnte nicht geladen werden. GGUF-Datei, freien RAM und Runtime-Version pruefen.");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size;
        cp.n_batch = 256;
        cp.n_ubatch = gpu ? 1 : 32;
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
        e.context_size = context_size;
        e.gpu = gpu;
        e.backend = gpu ? std::string("Vulkan - ") + ggml_backend_dev_description(devices[0]) : "CPU";
        e.templates = std::move(templates);
        e.model = std::move(model);
        e.context = std::move(context);
        return result_bytes(env, common_json({{"backend",e.backend},{"context",llama_n_ctx(e.context.get())},{"metadata",metadata},
            {"reference_cpu",bonsai_reference_cpu.load()}}).dump());
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_de_bonsai_offline_NativeEngine_configure(JNIEnv * env, jclass, jlong handle,
                                             jint threads, jint batch_threads, jint micro_batch) {
    try {
        auto & e = engine(handle);
        if (!e.model) throw std::runtime_error("Kein Modell geladen.");
        if (threads < 1 || threads > 8 || batch_threads < 1 || batch_threads > 8 ||
            micro_batch < 1 || micro_batch > 256 || (micro_batch & (micro_batch - 1)))
            throw std::runtime_error("Ungueltiges Optimierungsprofil.");
        if (e.stop) throw std::runtime_error("Abgebrochen.");
        auto cp = llama_context_default_params();
        cp.n_ctx = e.context_size;
        cp.n_batch = 256;
        cp.n_ubatch = micro_batch;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = batch_threads;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        cp.offload_kqv = e.gpu;
        cp.op_offload = e.gpu;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.no_perf = false;
        cp.abort_callback = abort_compute;
        cp.abort_callback_data = &e;
        e.context.reset(); // Release the previous KV allocation before creating another.
        e.context.reset(llama_init_from_model(e.model.get(), cp));
        if (!e.context) throw std::runtime_error("Optimierungsprofil konnte keinen Kontext anlegen.");
    } catch (const std::exception & e) { fail(env, e); }
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
        llama_memory_clear(llama_get_memory(e.context.get()), true);
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
        llama_memory_clear(llama_get_memory(e.context.get()), true);
        return result_bytes(env, common_json({{"prefill_ms",prefill}, {"decode_ms",decode},
            {"decode_tokens",steps}, {"prompt_tokens",tokens.size()},
            {"tokens_per_second",steps * 1000.0 / std::max(decode, 0.001)},
            {"score_ms",prefill + decode * 128.0 / steps},
            {"fingerprint",std::to_string(fingerprint)}, {"signature",signature}, {"top_tokens",top_tokens}}).dump());
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_bonsai_offline_NativeEngine_generate(JNIEnv * env, jclass, jlong handle, jobjectArray roles,
                                            jobjectArray contents, jint limit, jint seed, jobject callback, jbyteArray grammar) {
    try {
        auto & e = engine(handle);
        if (!e.context || !e.model) throw std::runtime_error("Bitte zuerst ein Modell laden.");
        if (limit < 16 || limit > 2048) throw std::runtime_error("Ungueltiges Antwortlimit.");
        auto count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents) || !count) throw std::runtime_error("Ungueltiger Chat.");
        common_chat_templates_inputs input;
        input.enable_thinking = false;
        input.use_jinja = true;
        input.add_generation_prompt = true;
        for (jsize i = 0; i < count; ++i) {
            auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            common_chat_msg msg;
            msg.role = bytes(env, r); msg.content = bytes(env, c);
            env->DeleteLocalRef(r); env->DeleteLocalRef(c);
            input.messages.push_back(std::move(msg));
        }
        auto formatted = common_chat_templates_apply(e.templates.get(), input);
        auto vocab = llama_model_get_vocab(e.model.get());
        auto tokens = tokenize(vocab, formatted.prompt);
        if (tokens.empty()) throw std::runtime_error("Leerer Prompt.");
        if (tokens.size() + static_cast<size_t>(limit) > llama_n_ctx(e.context.get()))
            throw std::runtime_error("Chat passt nicht mehr in den Kontext. Neuen Chat starten, das Antwortlimit verkleinern oder den Kontext erhoehen.");
        auto cb_class = env->GetObjectClass(callback);
        auto on_chunk = env->GetMethodID(cb_class, "onBytes", "([B)V");
        env->DeleteLocalRef(cb_class);
        if (!on_chunk) throw std::runtime_error("Streaming-Callback fehlt.");
        llama_memory_clear(llama_get_memory(e.context.get()), true);
        llama_perf_context_reset(e.context.get());
        auto start = Clock::now();
        const int batch = static_cast<int>(llama_n_batch(e.context.get()));
        for (size_t offset = 0; offset < tokens.size() && !e.stop; offset += batch) {
            auto n = static_cast<int32_t>(std::min<size_t>(batch, tokens.size() - offset));
            auto b = llama_batch_get_one(tokens.data() + offset, n);
            int rc = llama_decode(e.context.get(), b);
            if (rc != 0 && !e.stop) throw std::runtime_error("Prompt-Verarbeitung fehlgeschlagen (Speicher oder Backend). Bitte CPU/kleineren Kontext versuchen.");
        }
        double prefill_ms = elapsed(start), first_token_ms = 0;
        auto generation_start = Clock::now(), last_emit = Clock::now();
        const auto grammar_text = grammar ? bytes(env, grammar) : std::string();
        Sampler sampler(bonsai_chat_sampler(vocab,static_cast<uint32_t>(seed),grammar_text.c_str()), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Sampler konnte nicht angelegt werden.");
        std::string pending;
        int generated = 0;
        bool eos = false;
        auto emit = [&] {
            auto n = complete_utf8_prefix(pending);
            if (!n) return;
            auto data = result_bytes(env, pending.substr(0,n));
            if (!data) throw std::runtime_error("Kein Speicher fuer die Antwort.");
            env->CallVoidMethod(callback, on_chunk, data);
            env->DeleteLocalRef(data);
            pending.erase(0,n);
            last_emit = Clock::now();
            if (env->ExceptionCheck()) throw std::runtime_error("Antwort-Streaming unterbrochen.");
        };
        while (generated < limit && !e.stop) {
            const float * logits = llama_get_logits_ith(e.context.get(), -1);
            if (!logits || !std::all_of(logits, logits + llama_vocab_n_tokens(vocab), [](float x) { return std::isfinite(x); }))
                throw std::runtime_error("Das Modell liefert ungueltige Rechenwerte. CPU-Kompatibilitaetsmodus oder Modelltest verwenden.");
            auto token = llama_sampler_sample(sampler.get(), e.context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { eos = true; break; }
            if (!generated) first_token_ms = elapsed(start);
            pending += piece(vocab, token);
            ++generated;
            if (elapsed(last_emit) >= 50) emit();
            if (generated >= limit || e.stop) break;
            auto b = llama_batch_get_one(&token, 1);
            int rc = llama_decode(e.context.get(), b);
            if (rc != 0 && !e.stop) throw std::runtime_error("Antwortberechnung fehlgeschlagen. CPU oder kleineren Kontext versuchen.");
        }
        emit();
        const double generation_ms = elapsed(generation_start);
        return result_bytes(env, common_json({{"prompt_tokens",tokens.size()}, {"output_tokens",generated},
            {"prefill_ms",prefill_ms}, {"first_token_ms",first_token_ms},
            {"generation_ms",generation_ms}, {"tokens_per_second", generation_ms > 0 ? generated*1000.0/generation_ms : 0},
            {"stopped",e.stop.load()}, {"limit_reached",!eos && generated >= limit}}).dump());
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}
