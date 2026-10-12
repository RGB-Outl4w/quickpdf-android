package com.quickpdf.reader

import android.app.Application
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.Updates
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class QuickPdfApp : Application() {
    lateinit var library: Library
        private set
    lateinit var updates: Updates
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        library = Library(this)
        updates = Updates(this)
    }
}
