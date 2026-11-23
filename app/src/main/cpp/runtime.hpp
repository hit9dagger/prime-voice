#pragma once
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace prime {
class Runtime {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    std::chrono::steady_clock::time_point deadline;
    static bool abort(void *data) {
        return std::chrono::steady_clock::now() > static_cast<Runtime *>(data)->deadline;
    }
public:
    Runtime(const std::string &path, int threads) {
        llama_backend_init();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) throw std::runtime_error("GGUF model nelze nacist");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 256; cp.n_ubatch = 128;
        cp.n_threads = std::clamp(threads, 1, 4); cp.n_threads_batch = cp.n_threads;
        cp.offload_kqv = false; cp.op_offload = false;
        cp.abort_callback = abort; cp.abort_callback_data = this;
        deadline = std::chrono::steady_clock::time_point::max();
        context = llama_init_from_model(model, cp);
        if (!context) { llama_model_free(model); model = nullptr; throw std::runtime_error("Nedostatek pameti pro kontext AI"); }
    }
    ~Runtime() { if (context) llama_free(context); if (model) llama_model_free(model); }
    Runtime(const Runtime &) = delete;
    std::string translate(const std::string &prompt, int timeoutMs = 18000, int maxTokens = 256) {
        deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        const auto *vocab = llama_model_get_vocab(model);
        const char *tmpl = llama_model_chat_template(model, nullptr);
        if (!tmpl) throw std::runtime_error("Model nema overenou sablonu dialogu");
        llama_chat_message message {"user", prompt.c_str()};
        std::vector<char> formatted(prompt.size() * 2 + 2048);
        int n = llama_chat_apply_template(tmpl, &message, 1, true, formatted.data(), formatted.size());
        if (n < 0) throw std::runtime_error("Nepodporovana sablona GGUF modelu");
        if (n > int(formatted.size())) {
            formatted.resize(n + 1);
            n = llama_chat_apply_template(tmpl, &message, 1, true, formatted.data(), formatted.size());
        }
        // Qwen3's documented non-thinking mode: finish the empty reasoning block in
        // the assistant prefix, so generation is solely the user-visible translation.
        char architecture[64] = {};
        llama_model_meta_val_str(model, "general.architecture", architecture, sizeof(architecture));
        if (std::string(architecture) == "qwen3") {
            const std::string prefix = "<think>\n\n</think>\n\n";
            formatted.resize(n + prefix.size());
            std::copy(prefix.begin(), prefix.end(), formatted.begin() + n);
            n += prefix.size();
        }
        std::vector<llama_token> tokens(2048);
        int nt = llama_tokenize(vocab, formatted.data(), n, tokens.data(), tokens.size(), true, true);
        if (nt <= 0 || nt + maxTokens >= 2048) throw std::runtime_error("Dialog je prilis dlouhy pro kontext modelu");
        llama_memory_clear(llama_get_memory(context), true);
        for (int start = 0; start < nt; start += 256) {
            auto batch = llama_batch_get_one(tokens.data() + start, std::min(256, nt - start));
            if (llama_decode(context, batch) != 0) throw std::runtime_error("AI prekrocila limit nebo selhalo zpracovani vstupu");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string out;
        for (int i = 0; i < maxTokens; ++i) {
            if (abort(this)) throw std::runtime_error("AI prekrocila casovy limit");
            llama_token token = llama_sampler_sample(sampler.get(), context, -1);
            if (llama_vocab_is_eog(vocab, token)) return out;
            std::vector<char> piece(256);
            int bytes = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (bytes < 0) { piece.resize(-bytes); bytes = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (bytes < 0) throw std::runtime_error("Neplatny token modelu");
            out.append(piece.data(), bytes);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context, batch) != 0) throw std::runtime_error("AI nedokoncila vetu");
        }
        // Never dub a truncated generation as if it were a complete sentence.
        throw std::runtime_error("AI nedokoncila preklad v limitu tokenu");
    }
};
}
