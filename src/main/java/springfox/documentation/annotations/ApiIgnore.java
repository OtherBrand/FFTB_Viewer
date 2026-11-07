package springfox.documentation.annotations;

/**
 * Shim replacing the removed springfox dependency (R6): controllers import this
 * annotation purely to SUPPRESS docs. Semantics preserved — a no-op marker.
 */
public @interface ApiIgnore {
}
