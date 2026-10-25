package app.umuo.core

import app.umuo.core.playback.brightnessAfterDrag
import kotlin.test.*

class GestureBrightnessTest {
    @Test fun smallDragAtTwoPercentChangesSmoothlyInsteadOfJumpingToMaximum() {
        assertEquals(.03f,brightnessAfterDrag(.02f,-10f,1000),.00001f)
        assertEquals(.07f,brightnessAfterDrag(.02f,-50f,1000),.00001f)
        var previous=.02f
        for(pixels in 1..100) {
            val next=brightnessAfterDrag(.02f,-pixels.toFloat(),1000)
            assertTrue(next>=previous)
            assertTrue(next-previous<=.00101f)
            previous=next
        }
        assertEquals(.12f,previous,.00001f)
    }
    @Test fun clampsAtBoundsAndIgnoresUnmeasuredViewport() {
        assertEquals(.02f,brightnessAfterDrag(.02f,10f,1000))
        assertEquals(1f,brightnessAfterDrag(.02f,-1000f,1000))
        assertEquals(.5f,brightnessAfterDrag(.5f,-10f,0))
        assertEquals(.5f,brightnessAfterDrag(.5f,10f,-1))
        assertEquals(.4f,brightnessAfterDrag(.5f,100f,1000),.00001f)
    }
}
