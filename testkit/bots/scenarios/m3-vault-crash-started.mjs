// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A vault withdrawal stopped at VAULT_WITHDRAW_STARTED (see lib/vault-crash.mjs).
import { vaultCrashScenario } from '../lib/vault-crash.mjs'

export default vaultCrashScenario('m3-vault-crash-started', 'VAULT_WITHDRAW_STARTED', false)
