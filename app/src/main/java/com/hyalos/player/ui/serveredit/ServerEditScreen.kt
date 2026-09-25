package com.hyalos.player.ui.serveredit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hyalos.player.R
import com.hyalos.player.data.ServerConfig.Problem
import com.hyalos.player.ui.common.text
import com.hyalos.player.ui.serveredit.ServerEditViewModel.TestState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(viewModel: ServerEditViewModel, onDone: () -> Unit) {
    LaunchedEffect(viewModel.saved) { if (viewModel.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (viewModel.isNew) R.string.server_add else R.string.server_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = viewModel.loaded) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { insets ->
        if (!viewModel.loaded) {
            Box(Modifier.fillMaxSize().padding(insets), Alignment.Center) { CircularProgressIndicator() }
        } else {
            Form(viewModel, Modifier.padding(insets))
        }
    }
}

@Composable
private fun Form(viewModel: ServerEditViewModel, modifier: Modifier) {
    val form = viewModel.form
    val problem = viewModel.problem

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Field(
            value = form.name,
            onChange = { v -> viewModel.update { it.copy(name = v) } },
            label = R.string.field_name,
            error = problem.takeIf { it == Problem.NAME_EMPTY },
        )
        Field(
            value = form.host,
            onChange = { v -> viewModel.update { it.copy(host = v) } },
            label = R.string.field_host,
            placeholder = R.string.field_host_hint,
            keyboardType = KeyboardType.Uri,
            error = problem.takeIf {
                it == Problem.HOST_EMPTY || it == Problem.HOST_INVALID || it == Problem.PORT_IN_HOST
            },
        )
        Field(
            value = form.port,
            onChange = { v -> viewModel.update { it.copy(port = v) } },
            label = R.string.field_port,
            placeholder = R.string.field_port_hint,
            keyboardType = KeyboardType.Number,
            error = problem.takeIf { it == Problem.PORT_INVALID },
        )
        Field(
            value = form.share,
            onChange = { v -> viewModel.update { it.copy(share = v) } },
            label = R.string.field_share,
            placeholder = R.string.field_share_hint,
            error = problem.takeIf { it == Problem.SHARE_EMPTY || it == Problem.SHARE_INVALID },
        )
        Field(
            value = form.startPath,
            onChange = { v -> viewModel.update { it.copy(startPath = v) } },
            label = R.string.field_start_path,
            keyboardType = KeyboardType.Uri,
            error = problem.takeIf { it == Problem.START_PATH_INVALID },
        )
        Field(
            value = form.username,
            onChange = { v -> viewModel.update { it.copy(username = v) } },
            label = R.string.field_username,
        )
        PasswordField(
            value = form.password,
            onChange = { v -> viewModel.update { it.copy(password = v) } },
            supporting = if (viewModel.isNew) null else stringResource(R.string.field_password_keep),
        )
        Field(
            value = form.domain,
            onChange = { v -> viewModel.update { it.copy(domain = v) } },
            label = R.string.field_domain,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.field_seal), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.field_seal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = form.smbSeal,
                onCheckedChange = { v -> viewModel.update { it.copy(smbSeal = v) } },
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        TestRow(viewModel)
    }
}

@Composable
private fun TestRow(viewModel: ServerEditViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val test = viewModel.test) {
            TestState.Running -> {
                OutlinedButton(onClick = viewModel::cancelTest) { Text(stringResource(R.string.cancel)) }
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.test_running))
            }
            else -> {
                OutlinedButton(onClick = viewModel::testConnection) {
                    Text(stringResource(R.string.test_connection))
                }
                when (test) {
                    TestState.Ok -> Text(
                        stringResource(R.string.test_ok),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is TestState.Failed -> Text(
                        test.error.text(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: Problem? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message())) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, supporting: String?) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.field_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    stringResource(if (visible) R.string.password_hide else R.string.password_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Problem.message(): Int = when (this) {
    Problem.NAME_EMPTY -> R.string.problem_name_empty
    Problem.HOST_EMPTY -> R.string.problem_host_empty
    Problem.HOST_INVALID -> R.string.problem_host_invalid
    Problem.PORT_IN_HOST -> R.string.problem_port_in_host
    Problem.SHARE_EMPTY -> R.string.problem_share_empty
    Problem.SHARE_INVALID -> R.string.problem_share_invalid
    Problem.START_PATH_INVALID -> R.string.problem_start_path_invalid
    Problem.PORT_INVALID -> R.string.problem_port_invalid
}
