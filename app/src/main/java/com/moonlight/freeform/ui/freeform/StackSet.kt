package com.moonlight.freeform.ui.freeform

/**
 * @author sunshine
 * @date 2021/3/18
 * Implements a stack that stays unique by leaning on collection semantics
 */
class StackSet {

    private val elementData = ArrayList<FreeformViewAbs>()

    /**
     * Push an element to the top of the stack while keeping it unique.
     * Time complexity O(n)
     */
    fun push(element: FreeformViewAbs) {
        elementData.remove(element)
        elementData.add(element)
    }

    fun pop(): FreeformViewAbs {
        return elementData.removeAt(elementData.size - 1)
    }

    fun peek(): FreeformViewAbs {
        return elementData[elementData.size - 1]
    }

    fun remove(element: FreeformViewAbs) {
        elementData.remove(element)
    }

    fun containsFreeform(packageName: String, userId: Int): Boolean {
        elementData.forEach {
            if (it.config.packageName == packageName && it.config.userId == userId) {
                return true
            }
        }
        return false
    }

    fun size(): Int {
        return elementData.size
    }

    fun get(index: Int): FreeformViewAbs {
        return elementData[index]
    }

    fun getByPackageName(packageName: String, userId: Int): FreeformViewAbs? {
        elementData.forEach {
            if (it.config.packageName == packageName && it.config.userId == userId) {
                return it
            }
        }
        return null
    }
}