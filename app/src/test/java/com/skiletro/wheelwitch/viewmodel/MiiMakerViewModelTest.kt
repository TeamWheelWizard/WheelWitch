package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.util.mii.MiiWadInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiiMakerViewModelTest {

    private val application: Application = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(MiiWadInstaller)
        every { MiiWadInstaller.getCachedWadFile(application) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(MiiWadInstaller)
        Dispatchers.resetMain()
    }

    @Test
    fun `companion factory constructs the view model from application extras`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val extras = MutableCreationExtras()
        extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application

        val vm = MiiMakerViewModel.Factory.create(MiiMakerViewModel::class.java, extras)

        assertThat(vm).isInstanceOf(MiiMakerViewModel::class.java)
    }
}