package app.simple.felicity.repository.shuffle

object Shuffle {

    /**
     * How far ahead the spread pass looks for an item from a different group when it finds
     * two neighbours sharing one. If every candidate inside the window belongs to the same
     * group then that group dominates the rest of the queue and the repeat is unavoidable,
     * so it is accepted rather than scanning the whole list for a swap that does not exist.
     */
    private const val LOOKAHEAD = 32

    /**
     * Shuffles [items] uniformly, then nudges the result so the same artist does not play
     * back-to-back.
     *
     * The randomization is a plain Fisher-Yates pass ([List.shuffled]), so every item is
     * equally likely to land at any position and every call draws from the whole list.
     * [spreadGroups] then makes a single forward pass and repairs only the handful of places
     * where two neighbours share a [groupBy] value; the rest of the uniform order is left
     * exactly as it came out of the shuffle.
     *
     * If [currentItem] is provided it is removed from the pool before shuffling and
     * re-inserted at position 0 at the end, so the currently playing song is never
     * interrupted mid-queue rebuild. Its group seeds the spread pass as well, so the item
     * that follows it is from a different artist too.
     *
     * @param items       The original list to shuffle.
     * @param groupBy     Selector used to group items — typically returns the artist name.
     * @param currentItem The item that should stay anchored at position 0, or null.
     */
    fun <T> smartShuffle(
            items: List<T>,
            groupBy: (T) -> Any,
            currentItem: T? = null
    ): List<T> {
        if (items.isEmpty()) return emptyList()

        val listToShuffle = if (currentItem != null) {
            items.filter { it != currentItem }
        } else {
            items
        }

        if (listToShuffle.isEmpty()) return listOfNotNull(currentItem)

        val shuffledResult = listToShuffle.shuffled().toMutableList()

        spreadGroups(shuffledResult, groupBy, currentItem?.let(groupBy))

        currentItem?.let { shuffledResult.add(0, it) }

        return shuffledResult
    }

    /**
     * Breaks up runs of the same [groupBy] value in [list] with one forward pass.
     *
     * Whenever an item repeats its predecessor's group it is swapped with the nearest later
     * item from a different group. The displaced item lands further down the list and is
     * re-examined when the pass reaches it, so a single pass is enough and the work stays
     * bounded at [LOOKAHEAD] comparisons per position.
     *
     * @param leadingGroup Group of the item that gets prepended to [list] afterwards (the
     *                     anchored current song), or null when nothing is prepended.
     */
    private fun <T> spreadGroups(list: MutableList<T>, groupBy: (T) -> Any, leadingGroup: Any?) {
        var previousGroup = leadingGroup

        for (index in list.indices) {
            if (groupBy(list[index]) != previousGroup) {
                previousGroup = groupBy(list[index])
                continue
            }

            val limit = minOf(index + LOOKAHEAD, list.lastIndex)
            var swapWith = -1

            for (candidate in index + 1..limit) {
                if (groupBy(list[candidate]) != previousGroup) {
                    swapWith = candidate
                    break
                }
            }

            if (swapWith != -1) {
                val displaced = list[index]
                list[index] = list[swapWith]
                list[swapWith] = displaced
            }

            previousGroup = groupBy(list[index])
        }
    }
}
