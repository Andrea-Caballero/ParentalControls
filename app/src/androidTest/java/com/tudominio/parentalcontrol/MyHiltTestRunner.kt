package com.tudominio.parentalcontrol

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

import dagger.hilt.android.testing.HiltTestApplication

class MyHiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}