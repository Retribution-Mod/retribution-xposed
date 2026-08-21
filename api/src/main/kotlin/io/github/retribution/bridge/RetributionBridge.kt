package io.github.retribution.bridge

/**
 * - JS -> native: `{ Retribution: { method, args: [...] } }` passed to either `RNSVGRenderableManager.getBBox` (sync, fast)
 *   or `FileReaderModule.readAsDataURL` (alternative path).
 *
 * - Native -> JS: JS registers a callable module named `RetributionBridge`; native code invokes
 *   `ReactInstance.callFunctionOnModule("RetributionBridge", method, NativeArray)`.
 *   JS replies via `Retribution.__callableReturn`.
 */
interface RetributionBridge {
    /**
     * Registers a native method callable from JS.
     *
     * If [name] is already registered, the new handler replaces the old one and a warning is logged.
     *
     * Arguments and return values are converted by React Native:
     * https://github.com/facebook/react-native/blob/main/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt
     *
     * `Unit` is converted to `null`.
     */
    fun registerMethod(name: String, handler: (args: List<Any?>) -> Any?)

    /**
     * Registers a suspending native method callable from JS.
     *
     * Only callable through the async (promise-based) bridge path.
     * The handler runs off the React native-modules thread so it doesn't block the bridge.
     *
     * Semantics and argument/return conversion match [registerMethod].
     */
    fun registerAsyncMethod(name: String, handler: suspend (args: List<Any?>) -> Any?)

    /**
     * Invokes a JS method on the `RetributionBridge` callable module and awaits the `Retribution.__callableReturn` reply.
     *
     * Throws if JS responds with `{ error: ... }`. May suspend indefinitely if JS never replies.
     */
    suspend fun callJSMethod(name: String, args: List<Any?> = emptyList()): Any?
}
