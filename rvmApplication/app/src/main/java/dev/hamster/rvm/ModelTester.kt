package dev.hamster.rvm

import android.content.Context
import android.util.Log

class ModelTester(val context: Context) {
    
    val TAG = "ModelTester"
    private val model = TfliteModelRunner(context)
    
    fun testModelForDummyInputs(modelFileName: String, useGPU: Boolean = false){
        model.loadModel(modelFileName, useGPU = useGPU)
        Log.d(TAG, "testVDA: Model $modelFileName loaded")
        model.testDummyInputs()
        Log.d(TAG, "testVDA: Model $modelFileName tested")
        model.close()
    }
    
}