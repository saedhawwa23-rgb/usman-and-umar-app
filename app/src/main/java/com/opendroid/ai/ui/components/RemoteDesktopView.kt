package com.opendroid.ai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.remote.protocol.InputEvent
import com.opendroid.ai.remote.protocol.ScreenFrameCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Maps a position in the fitted image (excluding letterbox bars) to remote pixels. */
internal fun mapToRemote(
    positionX: Float,
    positionY: Float,
    containerWidth: Int,
    containerHeight: Int,
    remoteWidth: Int,
    remoteHeight: Int,
): Pair<Int, Int> {
    if (containerWidth <= 0 || containerHeight <= 0 || remoteWidth <= 0 || remoteHeight <= 0) return 0 to 0
    val scale = minOf(containerWidth.toFloat() / remoteWidth, containerHeight.toFloat() / remoteHeight)
    val drawnWidth = remoteWidth * scale
    val drawnHeight = remoteHeight * scale
    val offsetX = (containerWidth - drawnWidth) / 2f
    val offsetY = (containerHeight - drawnHeight) / 2f
    val x = ((positionX - offsetX) / scale).toInt().coerceIn(0, remoteWidth - 1)
    val y = ((positionY - offsetY) / scale).toInt().coerceIn(0, remoteHeight - 1)
    return x to y
}

/** Live remote screen; pointer gestures are translated to real desktop mouse events. */
@Composable
fun RemoteDesktopView(
    frame: ScreenFrameCodec.ScreenFrame?,
    modifier: Modifier = Modifier,
    monitorIndex: Int = 0,
    moveOnly: Boolean = false,
    onInput: (InputEvent) -> Unit,
) {
    if (frame == null) {
        Box(
            modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF101418)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Waiting for the first frame…", color = Color(0xFF9AA4B2), fontSize = 12.sp)
        }
        return
    }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, frame) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)?.asImageBitmap()
        }
    }
    val remoteWidth = frame.width
    val remoteHeight = frame.height
    val selectedMonitor = monitorIndex.coerceAtLeast(0)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(remoteWidth.toFloat() / remoteHeight)
            .background(Color.Black)
            .pointerInput(remoteWidth, remoteHeight, selectedMonitor, moveOnly) {
                fun sendClick(x: Int, y: Int, button: Int, count: Int = 1) {
                    onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    repeat(count) {
                        onInput(InputEvent(InputEvent.MOUSE_DOWN, x = x, y = y, button = button, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                        onInput(InputEvent(InputEvent.MOUSE_UP, x = x, y = y, button = button, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    }
                }
                detectTapGestures(
                    onTap = { offset ->
                        val (x, y) = mapToRemote(offset.x, offset.y, size.width, size.height, remoteWidth, remoteHeight)
                        if (moveOnly) onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                        else sendClick(x, y, button = 1)
                    },
                    onDoubleTap = { offset ->
                        val (x, y) = mapToRemote(offset.x, offset.y, size.width, size.height, remoteWidth, remoteHeight)
                        if (moveOnly) onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                        else sendClick(x, y, button = 1, count = 2)
                    },
                    onLongPress = { offset ->
                        val (x, y) = mapToRemote(offset.x, offset.y, size.width, size.height, remoteWidth, remoteHeight)
                        if (moveOnly) onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                        else sendClick(x, y, button = 3)
                    },
                )
            }
            .pointerInput(remoteWidth, remoteHeight, selectedMonitor, moveOnly) {
                var lastX = 0
                var lastY = 0
                detectDragGestures(
                    onDragStart = { offset ->
                        val (x, y) = mapToRemote(offset.x, offset.y, size.width, size.height, remoteWidth, remoteHeight)
                        lastX = x
                        lastY = y
                        onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                        if (!moveOnly) onInput(InputEvent(InputEvent.MOUSE_DOWN, x = x, y = y, button = 1, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    },
                    onDrag = { change, _ ->
                        val (x, y) = mapToRemote(change.position.x, change.position.y, size.width, size.height, remoteWidth, remoteHeight)
                        lastX = x
                        lastY = y
                        onInput(InputEvent(InputEvent.MOUSE_MOVE, x = x, y = y, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    },
                    onDragEnd = {
                        if (!moveOnly) onInput(InputEvent(InputEvent.MOUSE_UP, x = lastX, y = lastY, button = 1, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    },
                    onDragCancel = {
                        if (!moveOnly) onInput(InputEvent(InputEvent.MOUSE_UP, x = lastX, y = lastY, button = 1, monitor = selectedMonitor, screenWidth = remoteWidth, screenHeight = remoteHeight))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = "Live desktop screen", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: Text("Decoding frame…", color = Color(0xFF9AA4B2), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}
