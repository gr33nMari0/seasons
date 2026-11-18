// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /hof import is journalled like an archive: the server stops dead once the cell is emptied but
// before IMPORT_DONE, and the import finishes at startup with every item exactly once.
import { importCrashScenario } from '../lib/import-crash.mjs'

export default importCrashScenario('m1-import-crash-cleared', 'IMPORT_CLEARED')
