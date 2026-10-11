package staff.android.conversations

fun main() {
    val brokenSource = ControlledSource()
    val broken = BrokenSearch(brokenSource)
    broken.submit("cam")
    broken.submit("tea")
    brokenSource.calls[1].reply(Reply.Success(listOf("tea")))
    brokenSource.calls[0].reply(Reply.Success(listOf("camera")))
    check(broken.snapshot().query == "tea" && broken.snapshot().items == listOf("camera"))
    println("Broken after late Camera: ${broken.snapshot()}")

    val source = ControlledSource()
    val search = LatestSearch(source)
    search.submit("cam")
    search.submit("tea")
    source.calls[1].reply(Reply.Success(listOf("tea")))
    source.calls[0].reply(Reply.Success(listOf("camera")))
    check(search.snapshot().query == "tea" && search.snapshot().items == listOf("tea"))
    println("Repaired after late Camera: ${search.snapshot()}")
    search.clear()
    source.calls[1].reply(Reply.Success(listOf("tea")))
    check(search.snapshot() == SearchState())
    println("After clear and duplicate reply: ${search.snapshot()}")

    val stream = SequencedResults()
    stream.begin(7)
    check(stream.accept(StreamUpdate(7, 3, listOf("tea"), false)))
    check(!stream.accept(StreamUpdate(7, 2, listOf("camera"), false)))
    check(stream.accept(StreamUpdate(7, 5, listOf("tea", "bag"), true)))
    check(!stream.accept(StreamUpdate(7, 6, listOf("camera"), false)))
    println("Stream after 3, 2, terminal 5, 6: ${stream.snapshot()}")
}
