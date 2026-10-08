package noobanidus.mods.lootr.common.api.client;

public enum ClientTextureType {
  NEW,
  VANILLA,
  // Kept for API compatibility: the old texture set was removed, this is never returned any more.
  @Deprecated
  OLD;
}
