package coil.decode

import okio.BufferedSource
import java.io.Closeable
import java.io.File

class ImageSource(
    val source: BufferedSource,
    val file: File
): Closeable {

}
