package nl.rogro82.pipup

/// Offsets of stacking popups (0.27.0, #64). Pure arithmetic, so it is unit-tested apart from
/// the WindowManager.
///
/// Every popup window is placed by gravity plus an x/y offset. Against an edge (top, bottom,
/// left, right) the offset counts inwards from that edge, so a stack simply adds up the sizes
/// of the popups before it. On a centred axis (y for center_left/right, x for
/// top/bottom_center) the offset is measured from the centre: the first popup stays centred
/// and the next ones line up after it (down, or to the right).
object StackLayout {

    /// Offset along the stack axis for each popup, in stack order (oldest first).
    /// [sizes] = each window's size along that axis in px (0 while not laid out yet).
    fun offsets(sizes: List<Int>, centred: Boolean, margin: Int, gap: Int): List<Int> {
        val result = ArrayList<Int>(sizes.size)
        var next = if (centred) 0 else margin
        for ((i, size) in sizes.withIndex()) {
            if (centred && i > 0) next += (sizes[i - 1] + size) / 2 + gap
            result.add(next)
            if (!centred) next += size + gap
        }
        return result
    }
}
