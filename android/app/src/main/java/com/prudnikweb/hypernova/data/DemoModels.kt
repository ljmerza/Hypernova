package com.prudnikweb.hypernova.data

// The demo's model lists (FakeHub.models): what a Mac answers to GET /v1/models, so the new-session
// sheet's model picker looks as it does with a real one.

/** Claude Code 2.1.302's /model list on a Max plan, as the daemon passes it on. */
internal val DEMO_CLAUDE: List<ModelOption> = listOf(
    ModelOption(null, "Default", "claude-opus-5-5", "Best for everyday, complex tasks", 1_000_000),
    ModelOption("opus", "Opus 5.5", "claude-opus-5-5", "For complex work and everyday tasks", 1_000_000),
    ModelOption("fable", "Fable 5.1", "claude-fable-5-1", "For your toughest challenges", 1_000_000),
    ModelOption("sonnet", "Sonnet 5.5", "claude-sonnet-5-5", "Most efficient for simpler tasks", 1_000_000),
    ModelOption("haiku", "Haiku 5.5", "claude-haiku-5-5", "Fastest for quick answers", 1_000_000),
    ModelOption("claude-haiku-4-5-20251001", "Haiku 4.5", "claude-haiku-4-5-20251001", "Fastest for quick answers", 200_000, older = true),
    ModelOption("claude-sonnet-5", "Sonnet 5", "claude-sonnet-5", "Efficient for routine tasks", 1_000_000, older = true),
    ModelOption("claude-opus-5", "Opus 5", "claude-opus-5", "Best for everyday, complex tasks", 1_000_000, older = true),
    ModelOption("claude-fable-5", "Fable 5", "claude-fable-5", "Most capable for your hardest and longest-running tasks", 1_000_000, older = true),
    ModelOption("claude-opus-4-8", "Opus 4.8", "claude-opus-4-8", "Best for everyday, complex tasks", 1_000_000, older = true),
    ModelOption("claude-opus-4-7", "Opus 4.7", "claude-opus-4-7", "Best for everyday, complex tasks", 1_000_000, older = true),
    ModelOption("claude-opus-4-6", "Opus 4.6", "claude-opus-4-6", "Best for everyday, complex tasks", 200_000, older = true),
    ModelOption("claude-sonnet-4-6", "Sonnet 4.6", "claude-sonnet-4-6", "Efficient for routine tasks", 200_000, older = true),
)

/** The demo OpenRouter's own models (its settings), then part of the catalogue, newest first. */
internal fun demoOpenRouter(): List<ModelOption> {
    fun m(id: String, name: String, ctx: Long, input: Double, output: Double) = ModelOption(id, name, id, null, ctx, ModelPrice(input, output))
    return listOf(
        ModelOption(null, "GPT-5", "openai/gpt-5", null, 400_000, ModelPrice(1.25, 10.0), slot = "default"),
        ModelOption("sonnet", "Claude Sonnet 4.5", "anthropic/claude-sonnet-4.5", null, 1_000_000, ModelPrice(3.0, 15.0), slot = "sonnet"),
        m("deepseek/deepseek-v4.1-flash-1017", "DeepSeek V4.1 Flash 1017", 1_048_576, 0.15, 0.6),
        m("google/gemini-4-argon", "Gemini 4 Argon", 1_048_576, 2.0, 10.0),
        m("anthropic/claude-haiku-5.5", "Claude Haiku 5.5", 1_000_000, 0.1, 0.5),
        m("mistralai/mistral-large-4-0", "Mistral Large 4", 1_048_576, 0.68, 2.09),
        m("openai/gpt-6.1-sol-pro", "GPT-6.1 Sol Pro", 1_050_000, 2.0, 10.0),
        m("openai/gpt-6.1-sol", "GPT-6.1 Sol", 1_050_000, 2.0, 10.0),
        m("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", 1_000_000, 2.0, 10.0),
        m("z-ai/glm-5.3-prime", "GLM 5.3 Prime", 1_000_000, 2.8, 8.8),
        m("qwen/qwen3.8-max-prime", "Qwen3.8 Max Prime", 1_000_000, 4.0, 12.0),
        m("openai/gpt-6-luna", "GPT-6 Luna", 1_050_000, 0.1, 0.5),
        m("openai/gpt-6-sol", "GPT-6 Sol", 1_050_000, 2.0, 10.0),
        m("anthropic/claude-opus-5.5", "Claude Opus 5.5", 1_000_000, 4.0, 20.0),
        m("x-ai/grok-4.7", "Grok 4.7", 500_000, 2.0, 6.0),
        m("z-ai/glm-5.3-flash", "GLM 5.3 Flash", 1_048_576, 0.048, 0.2),
        m("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1_048_576, 0.072, 0.144),
        m("openai/gpt-6-astra", "GPT-6 Astra", 1_050_000, 10.0, 50.0),
        m("google/gemini-3.8-flash", "Gemini 3.8 Flash", 1_048_576, 0.825, 4.125),
        m("anthropic/claude-fable-5.1", "Claude Fable 5.1", 1_000_000, 10.0, 50.0),
        m("qwen/qwen3.8-flash", "Qwen3.8 Flash", 1_000_000, 0.15, 0.47),
        m("z-ai/glm-5.3", "GLM 5.3", 1_048_576, 0.079, 4.8),
        m("deepseek/deepseek-v4-pro-0813", "DeepSeek V4 Pro 0813", 1_048_576, 0.66, 1.98),
        m("x-ai/grok-4.6", "Grok 4.6", 500_000, 2.0, 6.0),
        m("anthropic/claude-opus-5", "Claude Opus 5", 1_000_000, 5.0, 25.0),
        m("moonshotai/kimi-k3", "Kimi K3", 1_048_576, 0.304, 11.936),
        m("openai/gpt-5.6-sol", "GPT-5.6 Sol", 1_050_000, 4.0, 20.0),
        m("qwen/qwen3-coder:free", "Qwen3 Coder (free)", 262_144, 0.0, 0.0),
    )
}
