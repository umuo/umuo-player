package app.umuo.core.model

/** 验证候选配置时隔离凭据；失败与取消都不修改正在使用的账号。 */
class PendingCredentials(
    private val store: CredentialStore,
    private val sourceId: String,
    private val inheritActive: Boolean,
    reuseDraft: Boolean
) : CredentialStore {
    private val suffixes = listOf("password", "token")
    private var promoted = false
    init {
        if(!reuseDraft)store.remove(pendingKey("password"))
        store.remove(pendingKey("token")) // Each attempt must authenticate against its own server.
    }
    private fun pendingKey(suffix: String) = "draft:$sourceId:$suffix"
    private fun suffix(key: String) = suffixes.firstOrNull { key == "$sourceId:$it" }
    override fun read(key: String): String? {
        val suffix = suffix(key) ?: return store.read(key)
        return if(promoted) store.read(key) else store.read(pendingKey(suffix)) ?: if(inheritActive && suffix=="password") store.read(key) else null
    }
    override fun write(key: String, value: String) {
        val suffix = suffix(key)
        store.write(if(promoted || suffix == null) key else pendingKey(suffix), value)
    }
    override fun remove(key: String) {
        val suffix = suffix(key)
        store.remove(if(promoted || suffix == null) key else pendingKey(suffix))
    }
    /** 先发布凭据，再提交配置事务；配置提交失败时恢复原凭据。 */
    fun commit(configuration: () -> Unit) {
        val old = suffixes.associateWith { store.read("$sourceId:$it") }
        val candidate = suffixes.associateWith { read("$sourceId:$it") }
        try {
            candidate.forEach { (suffix,value) -> if(value==null)store.remove("$sourceId:$suffix") else store.write("$sourceId:$suffix",value) }
            configuration()
            promoted = true
        } catch(e: Exception) {
            old.forEach { (suffix,value) -> if(value==null)store.remove("$sourceId:$suffix") else store.write("$sourceId:$suffix",value) }
            throw e
        }
        suffixes.forEach { runCatching {store.remove(pendingKey(it))} }
    }
}
