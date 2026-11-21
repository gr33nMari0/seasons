// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /hof stamp is journalled before it changes the world: the server stops dead once STAMP_STARTED is
// on disk, before any block is placed, and the stamp is finished at startup from its saved plan.
import { stampCrashScenario } from '../lib/stamp.mjs'

export default stampCrashScenario('m5-stamp-crash-started', 'STAMP_STARTED')
