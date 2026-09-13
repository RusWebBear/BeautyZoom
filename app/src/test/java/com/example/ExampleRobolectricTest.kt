package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SampleImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BeautyZoom", appName)
  }

  @Test
  fun `sample images generation works`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    for (type in SampleImages.SampleType.values()) {
      val bitmap = SampleImages.getSampleBitmap(context, type)
      assertNotNull(bitmap)
    }
  }
}
