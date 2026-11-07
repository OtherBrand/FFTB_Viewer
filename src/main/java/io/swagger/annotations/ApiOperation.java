package io.swagger.annotations;

/**
 * Shim replacing the springfox-provided swagger-annotations jar (R6): eight
 * controllers carry @ApiOperation descriptions. No-op marker; attributes kept
 * so existing usage compiles verbatim.
 */
public @interface ApiOperation {
    String value() default "";
    String notes() default "";
}
