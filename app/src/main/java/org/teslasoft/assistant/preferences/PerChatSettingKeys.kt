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
        "dalle_version",
        "imagine_command",

        // Voice & speech
        "audio",
        "silence_mode",
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
        "layout",
        "persona_id",
        "activation_prompt_id",
        "persona_activation_seeded",

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

        // Attachments (documents/images awaiting send)
        "pending_includes",
        "apply_model_rules",

        // Legacy (deprecated plaintext API key slot; secureApiKey migrates it out)
        "api_key"
    )
}
