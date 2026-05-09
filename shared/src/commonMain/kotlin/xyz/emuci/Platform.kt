package xyz.emuci

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform