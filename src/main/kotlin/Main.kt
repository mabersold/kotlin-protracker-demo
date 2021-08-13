fun main(args: Array<String>) {
    val loader = ProTrackerLoader()
    val module = loader.loadModule()

    println("Module title: ${module.title}")
}