/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package org.tensorflow.lite.examples.ocr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

  private val tfImageName = "tensorflow.jpg"
  private val androidImageName = "android.jpg"
  private val chromeImageName = "chrome.jpg"
  private lateinit var viewModel: MLExecutionViewModel
  private lateinit var resultImageView: ImageView
//  private lateinit var tfImageView: ImageView
  private lateinit var imageView: ImageView
//  private lateinit var androidImageView: ImageView
//  private lateinit var chromeImageView: ImageView
  private lateinit var chipsGroup: ChipGroup
  private lateinit var runButton: Button
  private lateinit var textPromptTextView: TextView

  private var useGPU = false
  private var selectedImageName = "tensorflow.jpg"
  private lateinit var cameraImage : Bitmap
  private var ocrModel: OCRModelExecutor? = null
  private val inferenceThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val mainScope = MainScope()
  private val mutex = Mutex()

  val REQUEST_CODE = 200

  private fun isPermissionsAllowed(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  }

  private fun askForPermissions(): Boolean {
    if (!isPermissionsAllowed()) {
      if (ActivityCompat.shouldShowRequestPermissionRationale(this as Activity,Manifest.permission.CAMERA)) {
        showPermissionDeniedDialog()
      } else {
        ActivityCompat.requestPermissions(this as Activity,arrayOf(Manifest.permission.CAMERA),REQUEST_CODE)
      }
      return false
    }
    return true
  }

  override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<String>,grantResults: IntArray) {
    when (requestCode) {
      REQUEST_CODE -> {
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          // permission is granted, you can perform your operation here
        } else {
          // permission is denied, you can ask for permission again, if you want
          showPermissionDeniedDialog()
        }
        return
      }
    }
  }

  private fun showPermissionDeniedDialog() {
    AlertDialog.Builder(this)
      .setTitle("Permission Denied")
      .setMessage("Permission is denied, Please allow permissions from App Settings.")
      .setPositiveButton("App Settings",
        DialogInterface.OnClickListener { dialogInterface, i ->
          // send to app settings if permission is denied permanently
          val intent = Intent()
          intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
          val uri = Uri.fromParts("package", getPackageName(), null)
          intent.data = uri
          startActivity(intent)
        })
      .setNegativeButton("Cancel",null)
      .show()
  }

  private fun capturePhoto() {
    if(askForPermissions()) {
      val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
      startActivityForResult(cameraIntent, REQUEST_CODE)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE && data != null){
      imageView.setImageBitmap(data.extras?.get("data") as Bitmap)
      cameraImage = data.extras?.get("data") as Bitmap
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.tfe_is_activity_main)

    val toolbar: Toolbar = findViewById(R.id.toolbar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayShowTitleEnabled(false)

//    tfImageView = findViewById(R.id.tf_imageview)
//    androidImageView = findViewById(R.id.android_imageview)
//    chromeImageView = findViewById(R.id.chrome_imageview)
    imageView = findViewById(R.id.imageView)
    //val candidateImageViews = arrayOf<ImageView>(tfImageView, androidImageView, chromeImageView)

    val assetManager = assets
//    try {
//
//      val tfInputStream: InputStream = assetManager.open(tfImageName)
//      val tfBitmap = BitmapFactory.decodeStream(tfInputStream)
//      tfImageView.setImageBitmap(tfBitmap)
//      val androidInputStream: InputStream = assetManager.open(androidImageName)
//      val androidBitmap = BitmapFactory.decodeStream(androidInputStream)
//      androidImageView.setImageBitmap(androidBitmap)
//      val chromeInputStream: InputStream = assetManager.open(chromeImageName)
//      val chromeBitmap = BitmapFactory.decodeStream(chromeInputStream)
//      chromeImageView.setImageBitmap(chromeBitmap)
//    } catch (e: IOException) {
//      Log.e(TAG, "Failed to open a test image")
//    }

//    for (iv in candidateImageViews) {
//      setInputImageViewListener(iv)
//    }
    setInputImageViewListener(imageView)



    resultImageView = findViewById(R.id.result_imageview)
    chipsGroup = findViewById(R.id.chips_group)
    textPromptTextView = findViewById(R.id.text_prompt)
    val useGpuSwitch: Switch = findViewById(R.id.switch_use_gpu)

    viewModel = AndroidViewModelFactory(application).create(MLExecutionViewModel::class.java)
    viewModel.resultingBitmap.observe(
      this,
      Observer { resultImage ->
        if (resultImage != null) {
          updateUIWithResults(resultImage)
        }
        enableControls(true)
      }
    )

    mainScope.async(inferenceThread) { createModelExecutor(useGPU) }

    useGpuSwitch.setOnCheckedChangeListener { _, isChecked ->
      useGPU = isChecked
      mainScope.async(inferenceThread) { createModelExecutor(useGPU) }
    }

    runButton = findViewById(R.id.rerun_button)
    runButton.setOnClickListener {
      enableControls(false)


      mainScope.async(inferenceThread) {

        mutex.withLock {
          if (ocrModel != null) {
            viewModel.onApplyModel(baseContext, cameraImage, ocrModel, inferenceThread)
          } else {
            Log.d(
              TAG,
              "Skipping running OCR since the ocrModel has not been properly initialized ..."
            )
          }
        }
      }
    }
    textPromptTextView.setText(getResources().getString(R.string.tfe_camera_image))
    setChipsToLogView(HashMap<String, Int>())
    enableControls(true)
    capturePhoto()
  }


  @SuppressLint("ClickableViewAccessibility")
  private fun setInputImageViewListener(iv: ImageView) {
    iv.setOnTouchListener(
      object : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent?): Boolean {
            capturePhoto()
            return false
//          if (v.equals(tfImageView)) {
//            selectedImageName = tfImageName
//            textPromptTextView.setText(getResources().getString(R.string.tfe_using_first_image))
//          } else if (v.equals(androidImageView)) {
//            selectedImageName = androidImageName
//            textPromptTextView.setText(getResources().getString(R.string.tfe_using_second_image))
//          } else if (v.equals(chromeImageView)) {
//            selectedImageName = chromeImageName
//            textPromptTextView.setText(getResources().getString(R.string.tfe_using_third_image))
//          }
//          return false
        }
      }
    )
  }

  private suspend fun createModelExecutor(useGPU: Boolean) {
    mutex.withLock {
      if (ocrModel != null) {
        ocrModel!!.close()
        ocrModel = null
      }
      try {
        ocrModel = OCRModelExecutor(this, useGPU)
      } catch (e: Exception) {
        Log.e(TAG, "Fail to create OCRModelExecutor: ${e.message}")
        val logText: TextView = findViewById(R.id.log_view)
        logText.text = e.message
      }
    }
  }

  private fun setChipsToLogView(itemsFound: Map<String, Int>) {
    chipsGroup.removeAllViews()

    for ((word, color) in itemsFound) {
      val chip = Chip(this)
      chip.text = word
      chip.chipBackgroundColor = getColorStateListForChip(color)
      chip.isClickable = false
      chipsGroup.addView(chip)
    }
    val labelsFoundTextView: TextView = findViewById(R.id.tfe_is_labels_found)
    if (chipsGroup.childCount == 0) {
      labelsFoundTextView.text = getString(R.string.tfe_ocr_no_text_found)
    } else {
      labelsFoundTextView.text = getString(R.string.tfe_ocr_texts_found)
    }
    chipsGroup.parent.requestLayout()
  }

  private fun getColorStateListForChip(color: Int): ColorStateList {
    val states =
      arrayOf(
        intArrayOf(android.R.attr.state_enabled), // enabled
        intArrayOf(android.R.attr.state_pressed) // pressed
      )

    val colors = intArrayOf(color, color)
    return ColorStateList(states, colors)
  }

  private fun setImageView(imageView: ImageView, image: Bitmap) {
    Glide.with(baseContext).load(image).override(250, 250).fitCenter().into(imageView)
  }

  private fun updateUIWithResults(modelExecutionResult: ModelExecutionResult) {
    setImageView(resultImageView, modelExecutionResult.bitmapResult)
    val logText: TextView = findViewById(R.id.log_view)
    logText.text = modelExecutionResult.executionLog

    setChipsToLogView(modelExecutionResult.itemsFound)
    enableControls(true)
  }

  private fun enableControls(enable: Boolean) {
    runButton.isEnabled = enable
  }

}
