import kotlin.js.Date

public actual fun currentTimeMillis(): Long = Date.now().toLong()