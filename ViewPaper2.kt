
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.my.dorokiclone.R
import com.my.dorokiclone.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun List<Placeable>.getSize(orientation: Orientation, dimension: Int): IntSize {
    return when (orientation) {
        Orientation.Horizontal -> {
            IntSize(
                width = dimension,
                height = maxOfOrNull { it.height } ?: 0
            )
        }
        Orientation.Vertical -> {
            IntSize(
                width = maxOfOrNull { it.height } ?: 0,
                height = dimension
            )
        }
    }
}

private fun Constraints.getMaxDimen(orientation: Orientation): Int {
    return when (orientation) {
        Orientation.Horizontal -> {
            maxWidth
        }
        Orientation.Vertical -> {
            maxHeight
        }
    }
}

private fun VelocityTracker.calculateVelocity(orientation: Orientation) = when (orientation) {
    Orientation.Horizontal -> calculateVelocity().x
    Orientation.Vertical -> calculateVelocity().y
}

private class GestureState(
    val orientation: Orientation,
    val coroutineScope: CoroutineScope,
    var itemDimen: Int = 0,
    val numberOfItems: Int
) {
    data class OffsetLimit(val max: Int, val min: Int)

    var currentIndex = 0
    var dragOffsetAnimate = Animatable(0f)
    val pointerInput: () -> Modifier = {

        Modifier.pointerInput(Unit) {

            // limit offset for drag
            val calculateOffsetLimit: () -> OffsetLimit = {
                val dimension = when (orientation) {
                    Orientation.Horizontal -> size.width
                    Orientation.Vertical -> size.height
                }
                OffsetLimit(
                    min = -dimension,
                    max = numberOfItems * itemDimen
                )
            }
            // listen gesture repeatedly
            forEachGesture {
                awaitPointerEventScope {
                    val down = awaitFirstDown()
                    val velocityTracker = VelocityTracker()  // init velocity tracker
                    // calculate drag change
                    val handleDragChange: (PointerInputChange) -> Unit = { change ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        coroutineScope.launch {
                            val dragChange = when (orientation) {
                                Orientation.Horizontal -> {
                                    change.positionChange().x
                                }
                                Orientation.Vertical -> {
                                    change.positionChange().y
                                }
                            }
                            val offsetLimit = calculateOffsetLimit()
                            dragOffsetAnimate.snapTo(
                                (dragOffsetAnimate.value - dragChange).coerceIn(
                                    offsetLimit.min.toFloat(),
                                    offsetLimit.max.toFloat()
                                )
                            )
                            println("===== dragOffSetAnimate: ${dragOffsetAnimate.value}")
                        }
                    }

                    // start to calculate drag change at first point base on orientation
                    when (orientation) {
                        Orientation.Horizontal -> {
                            horizontalDrag(down.id, handleDragChange)
                        }
                        Orientation.Vertical -> {
                            verticalDrag(down.id, handleDragChange)
                        }
                    }

                    // make drag change with velocity
                    val velocity = velocityTracker.calculateVelocity(orientation)
                    val decay = splineBasedDecay<Float>(this)
                    coroutineScope.launch {

                        var dragOffset = decay
                            .calculateTargetValue(dragOffsetAnimate.value, -velocity)

                        currentIndex = when {
                            velocity > 0 -> (currentIndex - 1).coerceAtLeast(0)
                            velocity < 0 -> (currentIndex + 1).coerceAtMost(
                                numberOfItems - 1
                            )
                            else -> currentIndex
                        }

                        val remainder = dragOffsetAnimate.value - (currentIndex * itemDimen)
                        val isHalfPassItem = remainder.absoluteValue > itemDimen / 2f
                        val isMinVelocity = velocity.absoluteValue < 1000
                        if (isHalfPassItem && isMinVelocity) {
                            currentIndex =
                                (if (remainder > 0) currentIndex + 1 else currentIndex - 1)
                                    .coerceIn(0, numberOfItems - 1)
                        }
                        dragOffset = when (!isMinVelocity || isHalfPassItem) {
                            true -> {
                                dragOffset.coerceIn(
                                    minimumValue = currentIndex * itemDimen.toFloat(),
                                    maximumValue = currentIndex * itemDimen.toFloat()
                                )
                            }
                            false -> {
                                dragOffset.coerceIn(
                                    minimumValue = currentIndex * itemDimen.toFloat(),
                                    maximumValue = currentIndex * itemDimen.toFloat()
                                )
                            }
                        }

                        dragOffsetAnimate.animateTo(
                            animationSpec = SpringSpec(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            targetValue = dragOffset,
                            initialVelocity = -velocity
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun <T> DorokiViewPaper(
    list: List<T>,
    contentItem: @Composable (T) -> Unit,
    modifier: Modifier,
    orientation: Orientation,
    itemFraction: Float = 1f
) {

    val coroutineScope = rememberCoroutineScope()
    val gestureStage =
        remember { GestureState(orientation, coroutineScope, numberOfItems = list.size) }

    Layout(
        modifier = modifier
            .clipToBounds()
            .then(gestureStage.pointerInput()),
        content = {
            list.map { item ->
                contentItem(item)
            }
        }
    ) { measurables, constraints ->

        val placeables = measurables.map { measurable ->
            measurable.measure(constraints)
        }
        val maxDimen = constraints.getMaxDimen(orientation)
        val sizeLayout = placeables.getSize(orientation, maxDimen)
        val itemDimen = (maxDimen * itemFraction).roundToInt()
        gestureStage.itemDimen = itemDimen
        layout(sizeLayout.width, sizeLayout.height) {

            val dragOffset = gestureStage.dragOffsetAnimate.value
            val first = ceil((dragOffset - itemDimen) / itemDimen)
                .toInt().coerceAtLeast(0)
            val last = ((maxDimen + dragOffset) / itemDimen)
                .toInt().coerceAtMost(list.lastIndex)
            for (i in first..last) {

                val offset = (i * itemDimen - dragOffset).roundToInt()
                placeables[i].place(
                    x = when (orientation) {
                        Orientation.Horizontal -> offset
                        Orientation.Vertical -> 0
                    },
                    y = when (orientation) {
                        Orientation.Horizontal -> 0
                        Orientation.Vertical -> offset
                    }
                )
            }
        }
    }
}

data class Intro(
    @DrawableRes val image: Int,
    val title: String,
    val desc: String,
    val bgColor: Color = Color.Red
)

val introList = listOf(
    Intro(R.drawable.ic_face_orange_500, "Make it r", "Description example", Color.Green),
    Intro(R.drawable.ic_face_orange_500, "Make it ra", "Description example", Color.Magenta),
    Intro(R.drawable.ic_face_orange_500, "Make it rai", "Description example", Color.Blue),
    Intro(R.drawable.ic_face_orange_500, "Make it rain", "Description example", Color.Cyan)
)

@Composable
@Preview(showBackground = true, name = "Doroki View Paper")
fun PreviewDorokiViewPaper() {
    AppTheme {
        DorokiViewPaper(
            list = introList,
            contentItem = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .background(it.bgColor)
                        .padding(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = it.image),
                        contentDescription = "image",
                        contentScale = ContentScale.Inside
                    )
                    Text(text = it.title)
                    Text(text = it.desc)
                }
            },
            modifier = Modifier.wrapContentSize()
                .width(300.dp),
            orientation = Orientation.Horizontal
        )
    }
}
