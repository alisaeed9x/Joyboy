package com.vanilla.tvlauncher

/**
 * مكدّس الرجوع (D-002 / DESIGN_NAV §3). Pure Kotlin (من غير Android) عشان يتاختبر على JVM.
 * الطبقات بتتسجّل من الأسفل للأعلى؛ dispatch() بيجرّب الأحدث تسجيلًا الأول،
 * وأول طبقة ترجّع true بتستهلك الضغطة. بيرجّع اسمها، أو null لو ولا واحدة استهلكت.
 */
class BackRouter {
    private class Layer(val name: String, val handler: () -> Boolean)
    private val layers = ArrayList<Layer>()

    fun register(name: String, handler: () -> Boolean) {
        layers.removeAll { it.name == name }
        layers.add(Layer(name, handler))
    }
    fun unregister(name: String) { layers.removeAll { it.name == name } }
    fun names(): List<String> = layers.map { it.name }

    fun dispatch(): String? {
        for (i in layers.indices.reversed()) if (layers[i].handler()) return layers[i].name
        return null
    }
}
