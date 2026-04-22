package com.avoqado.pos.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val tpvSettingsRepository: TpvSettingsRepository,
) : ViewModel() {

    val settings = tpvSettingsRepository.settings

    private val _isSavingTipTaxSetting = MutableStateFlow(false)
    val isSavingTipTaxSetting: StateFlow<Boolean> = _isSavingTipTaxSetting.asStateFlow()

    fun updateIncludeTaxInTipBase(enabled: Boolean) {
        viewModelScope.launch {
            _isSavingTipTaxSetting.value = true
            try {
                tpvSettingsRepository.setIncludeTaxInTipBase(enabled)
            } finally {
                _isSavingTipTaxSetting.value = false
            }
        }
    }
}
