package com.avoqado.pos.reservations.presentation.classsessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.product.CreateProductView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateClassSessionScreen(
    onClose: () -> Unit,
    viewModel: CreateClassSessionViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeSheet by remember { mutableStateOf<ClassCreateSheet?>(null) }
    var showCreateProduct by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(result) {
        result?.fold(
            onSuccess = { successMessage = it },
            onFailure = { scope.launch { snackbar.showSnackbar(it.message ?: "No se pudo crear la clase") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AvoqadoFullscreenHeader(
                title = "Crear clase",
                onNav = onClose,
                primaryActionText = "Crear",
                onPrimaryAction = { viewModel.submit() },
                primaryActionEnabled = draft.canSubmit && !isSubmitting,
                showDivider = true,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))

            SummaryRow(
                sectionLabel = "Clase",
                icon = Icons.Filled.Inventory2,
                primaryText = draft.productName,
                placeholder = "Seleccionar clase",
                secondaryText = draft.productDuration?.let { "$it min" },
                onClick = { activeSheet = ClassCreateSheet.PRODUCT },
            )
            SummaryRow(
                sectionLabel = "Fecha y hora",
                icon = Icons.Filled.Schedule,
                primaryText = "${formatDate(draft.date)} · ${draft.startTime.format(TIME)}-${draft.endTime.format(TIME)}",
                placeholder = "Elegir fecha y hora",
                onClick = { activeSheet = ClassCreateSheet.DATE_TIME },
            )
            SummaryRow(
                sectionLabel = "Capacidad",
                icon = Icons.Filled.Group,
                primaryText = "${draft.capacity} plazas",
                placeholder = "Definir capacidad",
                onClick = { activeSheet = ClassCreateSheet.CAPACITY },
            )
            SummaryRow(
                sectionLabel = "Staff (opcional)",
                icon = Icons.Filled.Person,
                primaryText = draft.assignedStaffName ?: "Cualquiera",
                placeholder = "Cualquiera",
                onClick = { activeSheet = ClassCreateSheet.STAFF },
            )
            SummaryRow(
                sectionLabel = "Repetición",
                icon = Icons.Filled.Repeat,
                primaryText = recurrenceSummary(draft),
                placeholder = "No se repite",
                onClick = { activeSheet = ClassCreateSheet.RECURRENCE },
            )

            Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
                SectionHeader("NOTAS INTERNAS (opcional)")
                OutlinedTextField(
                    value = draft.internalNotes.orEmpty(),
                    onValueChange = { text ->
                        viewModel.update { it.copy(internalNotes = text.ifBlank { null }) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Visible solo para staff") },
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }

    activeSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            com.avoqado.pos.designsystem.components.ImmersiveWindow()
            SheetHeader(sheet.title)
            when (sheet) {
                ClassCreateSheet.PRODUCT -> ProductPickerSection(
                    viewModel = viewModel,
                    onPicked = { activeSheet = null },
                    onCreate = { showCreateProduct = true },
                )
                ClassCreateSheet.DATE_TIME -> DateTimeSection(viewModel)
                ClassCreateSheet.CAPACITY -> CapacitySection(viewModel)
                ClassCreateSheet.STAFF -> StaffSection(viewModel)
                ClassCreateSheet.RECURRENCE -> RecurrenceSection(viewModel)
            }
        }
    }

    if (showCreateProduct) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        ModalBottomSheet(
            onDismissRequest = { showCreateProduct = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CreateProductView(
                productsRepository = viewModel.productsRepository,
                productType = "CLASS",
                onProductCreated = { product ->
                    viewModel.onProductCreated(product)
                    showCreateProduct = false
                    activeSheet = null
                },
                onDismiss = { showCreateProduct = false },
            )
        }
    }

    successMessage?.let { message ->
        AvoqadoSuccessToast(
            message = message,
            onDismiss = {
                successMessage = null
                onClose()
            },
        )
    }
}

private enum class ClassCreateSheet(val title: String) {
    PRODUCT("Clase"),
    DATE_TIME("Fecha y hora"),
    CAPACITY("Capacidad"),
    STAFF("Staff"),
    RECURRENCE("Repetición"),
}

@Composable
private fun ProductPickerSection(
    viewModel: CreateClassSessionViewModel,
    onPicked: () -> Unit,
    onCreate: () -> Unit,
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val classProducts = products.filter { it.type == "CLASS" && it.active != false }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        AddRow("Añadir nueva clase", onCreate)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (classProducts.isEmpty()) {
            EmptyClasses(onCreate)
        } else {
            classProducts.forEach { product ->
                ProductRow(product) {
                    viewModel.selectProduct(product)
                    onPicked()
                }
            }
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@Composable
private fun EmptyClasses(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(32.dp))
        }
        Text("No tienes clases configuradas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Crea tu primer producto tipo Clase", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(text = "Crear clase", onClick = onCreate, modifier = Modifier.fillMaxWidth(0.72f))
    }
}

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                listOfNotNull(product.duration?.let { "$it min" }, product.displayPrice).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateTimeSection(viewModel: CreateClassSessionViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val times = (6..22).map { LocalTime.of(it, 0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        StepperRow(
            label = formatDate(draft.date),
            onMinus = { viewModel.update { it.copy(date = it.date.minusDays(1)) } },
            onPlus = { viewModel.update { it.copy(date = it.date.plusDays(1)) } },
        )
        SectionHeader("HORA DE INICIO")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            times.forEach { time ->
                FilterChip(
                    selected = draft.startTime == time,
                    onClick = { viewModel.setStartTime(time) },
                    label = { Text(time.format(TIME)) },
                )
            }
        }
        Text(
            text = "Finaliza a las ${draft.endTime.format(TIME)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@Composable
private fun CapacitySection(viewModel: CreateClassSessionViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md)) {
        StepperRow(
            label = if (draft.capacity == 1) "1 plaza" else "${draft.capacity} plazas",
            onMinus = { viewModel.update { it.copy(capacity = (it.capacity - 1).coerceAtLeast(1)) } },
            onPlus = { viewModel.update { it.copy(capacity = (it.capacity + 1).coerceAtMost(500)) } },
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@Composable
private fun StaffSection(viewModel: CreateClassSessionViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val staff by viewModel.staff.collectAsStateWithLifecycle()
    Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md)) {
        SelectRow("Cualquiera", draft.assignedStaffId == null) {
            viewModel.update { it.copy(assignedStaffId = null, assignedStaffName = null) }
        }
        staff.forEach { member ->
            SelectRow(member.fullName, draft.assignedStaffId == member.id) {
                viewModel.update { it.copy(assignedStaffId = member.id, assignedStaffName = member.fullName) }
            }
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceSection(viewModel: CreateClassSessionViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val weekdays = listOf(1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S", 0 to "D")
    Column(
        modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repetir esta clase", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = draft.isRecurring,
                onCheckedChange = { on ->
                    viewModel.update { d ->
                        d.copy(
                            isRecurring = on,
                            weekdays = if (on && d.weekdays.isEmpty()) setOf(d.date.dayOfWeek.value % 7) else d.weekdays,
                        )
                    }
                },
            )
        }
        if (draft.isRecurring) {
            SectionHeader("DÍAS DE LA SEMANA")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            weekdays.forEach { (value, label) ->
                    FilterChip(
                        selected = value in draft.weekdays,
                        onClick = {
                            viewModel.update { d ->
                                val next = if (value in d.weekdays) d.weekdays - value else d.weekdays + value
                                d.copy(weekdays = next)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            SectionHeader("TERMINA")
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                FilterChip(
                    selected = draft.endMode == RecurrenceEndMode.COUNT,
                    onClick = { viewModel.update { it.copy(endMode = RecurrenceEndMode.COUNT) } },
                    label = { Text("Después de N sesiones") },
                )
                FilterChip(
                    selected = draft.endMode == RecurrenceEndMode.DATE,
                    onClick = { viewModel.update { it.copy(endMode = RecurrenceEndMode.DATE, endDate = it.endDate ?: it.date.plusWeeks(8)) } },
                    label = { Text("En una fecha") },
                )
            }
            if (draft.endMode == RecurrenceEndMode.COUNT) {
                StepperRow(
                    label = "${draft.occurrences} sesiones",
                    onMinus = { viewModel.update { it.copy(occurrences = (it.occurrences - 1).coerceAtLeast(1)) } },
                    onPlus = { viewModel.update { it.copy(occurrences = (it.occurrences + 1).coerceAtMost(104)) } },
                )
            } else {
                StepperRow(
                    label = formatDate(draft.endDate ?: draft.date),
                    onMinus = { viewModel.update { it.copy(endDate = (it.endDate ?: it.date).minusDays(1).coerceAtLeast(it.date)) } },
                    onPlus = { viewModel.update { it.copy(endDate = (it.endDate ?: it.date).plusDays(1)) } },
                )
            }
            Text(
                text = "Las sesiones se crearán todas a la misma hora. Si alguna fecha ya tiene una clase agendada, se omite automáticamente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
    }
}

@Composable
private fun SummaryRow(
    sectionLabel: String,
    icon: ImageVector,
    primaryText: String?,
    placeholder: String,
    secondaryText: String? = null,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
        SectionHeader(sectionLabel.uppercase())
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primaryText?.takeIf { it.isNotBlank() } ?: placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (primaryText.isNullOrBlank()) FontWeight.Normal else FontWeight.Medium,
                        color = if (primaryText.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    secondaryText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun StepperRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundIcon(Icons.Filled.Remove, "Disminuir", onMinus)
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        RoundIcon(Icons.Filled.Add, "Aumentar", onPlus)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

private fun recurrenceSummary(draft: CreateClassSessionDraft): String {
    if (!draft.isRecurring) return "No se repite"
    val dayLabels = listOf(1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S", 0 to "D")
        .filter { it.first in draft.weekdays }
        .joinToString("") { it.second }
    return if (draft.endMode == RecurrenceEndMode.COUNT) "$dayLabels · ${draft.occurrences} sesiones"
    else "$dayLabels · hasta ${draft.endDate?.format(DATE) ?: ""}"
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale("es"))).replaceFirstChar { it.uppercase() }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("es"))
