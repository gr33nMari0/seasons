// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// @isolated the End's dragon fight, which the reset can't put back
// Handing the dragon egg over stopped at EGG_JOURNALLED, the egg still on the portal and on disk
// (see lib/egg-crash.mjs).
import { eggCrashScenario } from '../lib/egg-crash.mjs'

export default eggCrashScenario('m4-egg-crash-journalled', 'EGG_JOURNALLED')
