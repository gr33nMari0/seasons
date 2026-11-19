// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Optional;
import java.util.UUID;

/**
 * What the game says about an entity that might be a pet.
 *
 * @param type the entity type's id without its namespace, such as {@code wolf}
 * @param tameable whether it is an animal the game lets players tame
 * @param tamed whether it is tamed now
 * @param owner who tamed it, as the game records it
 * @param carryingChest whether it is a donkey, mule or llama with a chest on
 * @param customName its name tag's text, as plain text
 */
public record PetInfo(
    UUID id,
    String type,
    boolean tameable,
    boolean tamed,
    Optional<PlayerId> owner,
    boolean carryingChest,
    Optional<String> customName,
    Position position) {}
