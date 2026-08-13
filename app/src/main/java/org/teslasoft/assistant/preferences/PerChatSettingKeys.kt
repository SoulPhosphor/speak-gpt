/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences

/**
 * The authoritative inventory of every key stored in a chat's own settings
 * file (`settings.<chatId>`), i.e. everything that must survive a chat
 * rename (auto-naming or manual — a rename changes the chat id and moves
 * the whole file).
 *
 * The rename itself does NOT read this list: ChatRenameTransaction copies
 * the settings file wholesale, so every key — including one added after
 * this list — is carried automatically. This registry exists so the set is
 * audited and visible: PerChatSettingKeysTest scans Preferences.kt for the
 * per-chat storage helpers and fails the build when a per-chat key is added
 * (or removed) without updating this list, and a second test proves the
 * rename copy carries every key registered here. History demanded this:
 * two hand-maintained copy blocks (ChatActivity auto-naming and the manual
 * rename dialog) drifted in opposite directions for months — one silently
 * dropped the persona/lorebooks/memory scene, the other silently reset the
 * voice settings.
 *
 * When you add a per-chat setting to Preferences.kt, add its storage key
 * here. Nothing else is required for it to survive renames.
 */
object PerChatSettingKeys {

    val ALL: Set<String> = setOf(
        // Generation
        "model",
        "max_tokens",
        "end",
        "prefix",
        "temperature",
        "topP",
        "frequency_penalty",
        "presence_penalty",
        "seed",
        "api_endpoint_id",
        "logit_biases_config_id",
        "function_calling",

        // Imaging
        "imageModel",
        "resolution",
        "imagine_command",

        // Voice & speech
        "audio",
        "always_speak_mode",
        "autoLangDetect",
        "voice",
        "tts_engine",
        "openai_voice",

        // Identity & prompts
        "prompt",
        "assistant_name",
        "avatar_type",
        "avatar_id",
        "persona_id",
        "activation_prompt_id",
        "persona_activation_seeded",
        // One-shot guard: a new chat restores the last successful provider/model
        // once. (The last-successful values themselves are global, not per-chat.)
        "provider_seeded",

        // Lorebooks
        "active_lorebook_ids",
        "lorebook_id", // legacy single-book key, still read as a fallback
        "lorebooks_seeded",

        // Memory system (tri-states and scene selectors)
        "memory_enabled",
        "lorebooks_enabled",
        "memory_excluded",
        "memory_world_id",
        "memory_campaign_id",
        "memory_roleplay_character_id",
        "memory_user_persona_id",
        "memory_project_id",

        // Conversation-level memory policy (canonical recovery plan §4.4/§4.5,
        // Phase 1): retrieval access per pool, extraction streams, do-not-
        // analyze, analysis note, use-default-vs-custom, and processing method.
        // Ordinary conversation metadata — separate from memories, never
        // provenance.
        "memory_general_access",
        "memory_companion_access",
        "analyze_general",
        "analyze_companion",
        "analyze_model_rules",
        "analysis_do_not",
        "analysis_note",
        "conversation_policy_mode",
        "analysis_processing_method",

        // Attachments (documents/images awaiting send)
        "pending_includes",
        "apply_model_rules",

        // Conversation summarizer (conversation-summary-plan.md decision 9:
        // the summary, bookmark, and error log are chat data — encrypted with
        // the chat, moved by renames, deleted with the chat)
        "use_summarizer",
        "summarizer_window",
        "summarizer_summary",
        "summarizer_folded",
        "summarizer_over_length",
        "summarizer_episode",
        "summarizer_errors",

        // Legacy (deprecated plaintext API key slot; secureApiKey migrates it out)
        "api_key"
    )
}
