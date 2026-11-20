// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// @isolated the End's dragon fight, which the reset can't put back
// Handing the dragon egg over stopped at EGG_RETURNED (see lib/egg-crash.mjs).
import { eggCrashScenario } from '../lib/egg-crash.mjs'

export default eggCrashScenario('m4-egg-crash-returned', 'EGG_RETURNED')
