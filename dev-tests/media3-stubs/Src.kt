// Stubs (فحص شكل الاستدعاءات فقط) — مش مشحونة في الـAPK.
package androidx.media3.datasource
class DefaultHttpDataSource { class Factory : DataSource.Factory { fun setDefaultRequestProperties(m: Map<String, String>) = this; fun setAllowCrossProtocolRedirects(b: Boolean) = this } }
interface DataSource { interface Factory }
