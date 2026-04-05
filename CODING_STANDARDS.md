# Entity Detective — Coding Standards

Lessons learned from analysis of [Carpet](https://github.com/gnembon/fabric-carpet)
and [FerriteCore](https://github.com/malte0811/FerriteCore). Apply these to every
new feature before committing.

---

## 1. Command handlers

**Every execute method must:**

```java
private static int executeXxx(CommandContext<CommandSourceStack> ctx, ...) {
    if (onCooldown(ctx)) return 0;          // rate-limit (exempt for console)
    CommandSourceStack source = ctx.getSource();
    try {
        // ... all logic here
        return 1;
    } catch (Exception e) {
        EntityDetective.LOGGER.error("EntityDetective: unexpected error in xxx command", e);
        source.sendFailure(Component.literal("An internal error occurred. Check server logs."));
        return 0;
    }
}
```

- Return `0` on failure, `1` on success — never throw from a command handler.
- Send `source.sendFailure()` with a clear message before returning `0`.
- Console source (`source.getEntity() == null`) is never rate-limited.

---

## 2. Null-check all MC lookups

These methods return `null` for missing or unregistered entries — always check:

| Call | Null when |
|------|-----------|
| `BuiltInRegistries.ENTITY_TYPE.getKey(type)` | Unregistered modded entity |
| `BuiltInRegistries.ITEM.getKey(item)` | Unregistered modded item |
| `server.getLevel(key)` | Dimension not loaded |
| `server.getPlayerList().getPlayerByName(name)` | Player offline |
| `source.getEntity()` | Console source |
| `source.getPlayer()` | Non-player source |

```java
// Good pattern:
@Nullable Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
String type = key != null ? key.toString() : "unknown:" + entity.getType().getDescriptionId();
```

Use `@Nullable` (from `org.jetbrains.annotations`) on any method that may return null.

---

## 3. Entity iteration

Always collect into a fresh list — never iterate live world entity storage:

```java
List<Entity> matched = new ArrayList<>();
world.getEntities(EntityTypeTest.forClass(Entity.class), predicate, matched);
// iterate `matched` safely
```

Never call `.remove()` or modify a list while iterating it.

---

## 4. instanceof before cast

```java
// Bad — ClassCastException for ItemEntity, ArmorStand, etc.
boolean persistent = ((Mob) entity).isPersistenceRequired();

// Good
boolean persistent = entity instanceof Mob mob && mob.isPersistenceRequired();
```

---

## 5. Integer arithmetic

Use `long` for any sum or product involving entity/item counts:

```java
long total = results.stream().mapToLong(r -> r.entities().size()).sum();
```

Cap numeric command arguments to prevent absurd AABB sizes:
```java
IntegerArgumentType.integer(1, 10_000)  // --range cap
```

---

## 6. Per-player state

Any `Map<UUID, ...>` keyed on player UUID must be cleaned up on disconnect:

```java
// In EntityDetective.onInitialize():
ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
    LocateCommand.clearCooldown(handler.player.getUUID());
    // + any other per-player maps
});
```

---

## 7. New command branches

Every new `.then(Commands.literal(...))` or `.then(Commands.argument(...))` leaf must:

- Inherit the parent `.requires(Permissions.require(...))` — verify it is not accidentally broadened.
- Be covered by the execute-method try/catch.
- Call `onCooldown(ctx)` at the top of its execute method.

---

## 8. Error messages

Include context in exception messages so bugs are diagnosable from logs alone:

```java
// Bad
throw new IllegalStateException("null result");

// Good
throw new IllegalStateException(
    "EntityDetective: null result in findByType — type=" + entityType +
    " world=" + world.dimension().location()
);
```

---

## 9. Pre-release checklist

Before bumping version and tagging a release, tick every item:

- [ ] All new execute methods have try/catch + `LOGGER.error` + `sendFailure`
- [ ] All new execute methods call `onCooldown(ctx)` at top
- [ ] All registry/level/player lookups null-checked before use
- [ ] All casts preceded by `instanceof`
- [ ] `long` used for entity/item count arithmetic
- [ ] Any new per-player `Map` has a `DISCONNECT` cleanup registered
- [ ] All new command branches have `.requires()` permission check
- [ ] `@Nullable` on any method that may return null
- [ ] `./gradlew compileJava` passes with no errors before committing
