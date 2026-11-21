// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// The server stops dead once every copy is placed, its markers air and the world saved, before the
// gallery file is written: startup places the copies again over themselves, harmlessly, writes the
// cells and closes the stamp.
import { stampCrashScenario } from '../lib/stamp.mjs'

export default stampCrashScenario('m5-stamp-crash-placed', 'STAMP_PLACED')
