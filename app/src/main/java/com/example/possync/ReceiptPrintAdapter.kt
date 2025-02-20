package com.example.possync

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import android.view.View
import java.io.FileOutputStream
import java.io.IOException

class ReceiptPrintAdapter(private val context: Context, private val view: View) : PrintDocumentAdapter() {

    private lateinit var pdfDocument: PrintedPdfDocument
    private val totalPages = 1

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        layoutResultCallback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        pdfDocument = PrintedPdfDocument(context, newAttributes)
        if (cancellationSignal.isCanceled) {
            layoutResultCallback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("receipt.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(totalPages)
            .build()
        layoutResultCallback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        writeResultCallback: WriteResultCallback
    ) {
        // Create one page
        val page = pdfDocument.startPage(0)
        if (cancellationSignal.isCanceled) {
            writeResultCallback.onWriteCancelled()
            pdfDocument.close()
            return
        }
        // Draw the view onto the PDF page's canvas
        view.draw(page.canvas)
        pdfDocument.finishPage(page)
        try {
            pdfDocument.writeTo(FileOutputStream(destination.fileDescriptor))
        } catch (e: IOException) {
            writeResultCallback.onWriteFailed(e.toString())
            return
        } finally {
            pdfDocument.close()
        }
        writeResultCallback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }
}
