
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.my.dorokiclone.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign

@Composable
fun <T : Any> Pager(
    items: List<T>,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Horizontal,
    initialIndex: Int = 0,
    itemFraction: Float = 1f,
    itemsSpacing: Dp = 1.dp,
    overshootFraction: Float = .5f,
    onItemSelect: (T) -> Unit = {},
    contentFraction: @Composable (T) -> Unit
) {
    require(initialIndex in 0..items.lastIndex) { "Initial index out of bounds" }
    require(itemFraction > 0f && itemFraction <= 1f) { "Item fraction must be in the [0f, 1f] range" }
    require(overshootFraction > 0f && itemFraction <= 1f) { "Overshoot fraction must be in the (0f, 1f) range" }
    val scope = rememberCoroutineScope()
    val state = rememberPagerStage()
    state.currentIndex = initialIndex
    state.numberOfItems = items.size
    state.itemFraction = itemFraction
    state.overshootFraction = overshootFraction
    state.itemSpacing = with(LocalDensity.current) { itemsSpacing.toPx() }
    state.orientation = orientation
    state.listener = { index -> onItemSelect(items[index]) }
    state.scope = scope

    Layout(
        content = {
            items.map { item ->
                Box(
                    modifier = when (orientation) {
                        Orientation.Horizontal -> Modifier.fillMaxWidth()
                        Orientation.Vertical -> Modifier.fillMaxHeight()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    contentFraction(item)
                }
            }
        },
        modifier = modifier
            .clipToBounds()
            .then(state.inputModifier)
    ) { measurables, constraints ->
        val dimension = constraints.dimension(orientation)
        val looseConstraints = constraints.toLooseConstraints(orientation, state.itemFraction)
        val placeables = measurables.map { measurable -> measurable.measure(looseConstraints) }
        val size = placeables.getSize(orientation, dimension)
        val itemDimension = (dimension * state.itemFraction).roundToInt()
        state.itemDimension = itemDimension
        val halfItemDimension = itemDimension / 2
        layout(size.width, size.height) {
            val centerOffSet = dimension / 2 - halfItemDimension
            val dragOffSet = state.dragOffset.value
            val roundedDragOffset = dragOffSet.roundToInt()
            val spacing = state.itemSpacing.roundToInt()
            val itemDimensionWithSpace = itemDimension + state.itemSpacing
            val first = ceil((dragOffSet - itemDimension - centerOffSet) / itemDimensionWithSpace)
                .toInt().coerceAtLeast(0)
            val last = ((dimension + dragOffSet - centerOffSet) / itemDimensionWithSpace)
                .toInt().coerceAtMost(items.lastIndex)

            for (i in first..last) {
                val offSet = i * (itemDimension + spacing) - roundedDragOffset + centerOffSet
                placeables[i].place(
                    x = when (orientation) {
                        Orientation.Horizontal -> offSet
                        Orientation.Vertical -> 0
                    },
                    y = when (orientation) {
                        Orientation.Horizontal -> 0
                        Orientation.Vertical -> offSet
                    }
                )
            }
        }
    }
}

/**
 * make copy of constraints where the minimum dimension not in the scroll direction is set to 0,
 * while on the scroll direction we set the minimum dimension to be equal to the maximum dimension
 */
private fun Constraints.dimension(orientation: Orientation) = when (orientation) {
    Orientation.Horizontal -> maxWidth
    Orientation.Vertical -> maxHeight
}

/**
 * return max size Constraints base on the orientation
 */
private fun Constraints.toLooseConstraints(
    orientation: Orientation,
    itemFraction: Float
): Constraints {
    val dimension = dimension(orientation)
    val adjustedDimension = (dimension * itemFraction).roundToInt()
    return when (orientation) {
        Orientation.Vertical -> copy(
            minWidth = 0,
            minHeight = adjustedDimension,
            maxHeight = adjustedDimension
        )
        Orientation.Horizontal -> copy(
            minWidth = adjustedDimension,
            maxWidth = adjustedDimension,
            minHeight = 0
        )
    }
}

/**
 * gets the size of our Parcelables based on the scroll direction.
 * scrolling horizontally: we want to know the maximum height of the Pager item
 * scrolling vertically: we want to calculate the maximum width
 */
private fun List<Placeable>.getSize(
    orientation: Orientation,
    dimension: Int,
): IntSize = when (orientation) {
    Orientation.Horizontal -> IntSize(
        dimension,
        maxByOrNull { it.height }?.height ?: 0
    )
    Orientation.Vertical -> IntSize(
        maxByOrNull { it.width }?.width ?: 0,
        dimension
    )
}

/**
 * provide the calculated velocity based on our scroll axis
 */
private fun VelocityTracker.calculateVelocity(orientation: Orientation) = when (orientation) {
    Orientation.Horizontal -> calculateVelocity().x
    Orientation.Vertical -> calculateVelocity().y
}

/**
 * calculates the pointer input change based on our scroll direction
 */
private fun PointerInputChange.calculateDragChange(orientation: Orientation) = when (orientation) {
    Orientation.Horizontal -> positionChange().x
    Orientation.Vertical -> positionChange().y
}

private class PagerState {
    var currentIndex by mutableStateOf(0)
    var numberOfItems by mutableStateOf(0)
    var itemFraction by mutableStateOf(0f)
    var overshootFraction by mutableStateOf(0f)
    var itemSpacing by mutableStateOf(0f)
    var itemDimension by mutableStateOf(0)
    var orientation by mutableStateOf(Orientation.Horizontal)
    var scope: CoroutineScope? by mutableStateOf(null)
    var listener: (Int) -> Unit by mutableStateOf({})
    var dragOffset = Animatable(0f)

    private val animationSpec = SpringSpec<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    val inputModifier = Modifier.pointerInput(numberOfItems) {

        fun itemIndex(offset: Int): Int = (offset / (itemDimension + itemSpacing)).roundToInt()
            .coerceIn(0, numberOfItems - 1)

        fun updateIndex(offset: Float) {
            val index = itemIndex(offset.roundToInt())
            if (index != currentIndex) {
                currentIndex = index
                listener(index)
            }
        }

        data class OffsetLimit(
            val min: Float,
            val max: Float
        )

        fun calculateOffsetLimit(): OffsetLimit {
            val dimension = when (orientation) {
                Orientation.Horizontal -> size.width
                Orientation.Vertical -> size.height
            }
            val itemSideMargin = (dimension - itemDimension) / 2f
            return OffsetLimit(
                min = -dimension * overshootFraction + itemSideMargin,
                max = numberOfItems * (itemDimension + itemSpacing) - (1f - overshootFraction) * dimension + itemSideMargin
            )
        }

        forEachGesture {
            awaitPointerEventScope {
                val tracker = VelocityTracker()
                val decay = splineBasedDecay<Float>(this)
                val down = awaitFirstDown()
                val offsetLimit = calculateOffsetLimit()
                val dragHandler = { change: PointerInputChange ->
                    scope?.launch {
                        val dragChange = change.calculateDragChange(orientation)
                        dragOffset.snapTo(
                            (dragOffset.value - dragChange).coerceIn(
                                offsetLimit.min,
                                offsetLimit.max
                            )
                        )
                        updateIndex(dragOffset.value)
                    }
                    tracker.addPosition(change.uptimeMillis, change.position)
                }
                when (orientation) {
                    Orientation.Horizontal -> horizontalDrag(down.id, dragHandler)
                    Orientation.Vertical -> verticalDrag(down.id, dragHandler)
                }
                val velocity = tracker.calculateVelocity(orientation)
                scope?.launch {
                    var targetOffset = decay.calculateTargetValue(dragOffset.value, -velocity)
                    val remainder = targetOffset.toInt().absoluteValue % itemDimension
                    val extra = if (remainder > itemDimension / 2f) 1 else 0
                    val lastVisibleIndex =
                        (targetOffset.absoluteValue / itemDimension.toFloat()).toInt() + extra
                    targetOffset =
                        (lastVisibleIndex * (itemDimension + itemSpacing) * targetOffset.sign)
                            .coerceIn(
                                0f,
                                (numberOfItems - 1).toFloat() * (itemDimension + itemSpacing)
                            )
                    dragOffset.animateTo(
                        animationSpec = animationSpec,
                        targetValue = targetOffset,
                        initialVelocity = -velocity
                    ) {
                        updateIndex(value)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPagerStage(): PagerState = remember { PagerState() }


val items = listOf(
    Color.Red,
    Color.Blue,
    Color.Green,
    Color.Yellow,
)

@Preview
@Composable
fun ViewPagerReview() {
    AppTheme() {
        Surface(color = MaterialTheme.colors.background) {
            Column(
                Modifier
                    .fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Pager(
                    items = items,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    itemFraction = 1f,
                    overshootFraction = 1f,
                    initialIndex = 3,
                    itemsSpacing = 0.dp,
                    contentFraction = { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(item),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.toString(),
                                modifier = Modifier.padding(all = 16.dp),
                                style = MaterialTheme.typography.h6
                            )
                        }
                    }
                )
            }
        }
    }
}
