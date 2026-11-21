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

package org.teslasoft.assistant.preferences.memory

/**
 * How much of one chat has been scanned for memories, for the Create Outline
 * screen's Memory Scan Progress line (owner design, Nov 18 2026): 100% when
 * nothing is left to scan, Unscanned when nothing has been scanned, otherwise
 * the first and last scanned message numbers. Message numbers count every
 * message in the chat from 1, the same numbering as each message's own
 * Message number. Computed by
 * [org.teslasoft.assistant.preferences.memory.scan.ScanChatStatus.coverage]
 * from the chat's bookmark and its current messages.
 */
object ChatScanCoverage {

    sealed interface Status {
        data object Unscanned : Status
        data object Complete : Status
        data class Range(val first: Int, val last: Int) : Status
    }
}
