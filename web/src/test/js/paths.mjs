// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Where the dashboard's files are, for the tests beside this one.
import { join } from 'node:path'

export const DASHBOARD = join(import.meta.dirname, '../../main/resources/com/petepeterepeat/seasons/web/dashboard')
export const MESSAGES = join(import.meta.dirname, '../../main/resources/com/petepeterepeat/seasons/web/messages.yml')
export const module = (name) => import(join(DASHBOARD, name))
