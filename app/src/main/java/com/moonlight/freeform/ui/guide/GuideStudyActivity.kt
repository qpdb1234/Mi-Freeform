package com.moonlight.freeform.ui.guide

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.moonlight.freeform.R
import com.moonlight.freeform.databinding.ActivityGuideStudyBinding

class GuideStudyActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }
    private lateinit var binding: ActivityGuideStudyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideStudyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callback = object : FreeformStudyViewNew.Callback {
            override fun onMoveClick() {
                binding.textViewGuideB.text = getString(R.string.guide_back)
                binding.textViewMiddle.alpha = 0f
                binding.textViewRight.alpha = 1f
                SecondFragment.callback?.onMoveClick()
            }

            override fun onCloseClick() {
                binding.textViewGuideB.text = getString(R.string.guide_to_full)
                SecondFragment.callback?.onCloseClick()
            }

            override fun onBackClick() {
                binding.textViewGuideB.text = getString(R.string.guide_close)
                binding.textViewRight.alpha = 0f
                binding.textViewLeft.alpha = 1f
                SecondFragment.callback?.onBackClick()
            }

            override fun onToBackStageClick() {
                binding.textViewGuideB.text = getString(R.string.guide_scale)
                binding.textViewRight.alpha = 0f
                SecondFragment.callback?.onToBackStageClick()
            }

            override fun onToFullClick() {
                binding.textViewGuideB.text = getString(R.string.guide_to_back_stage)
                binding.textViewRight.alpha = 1f
                binding.textViewLeft.alpha = 0f
                SecondFragment.callback?.onToFullClick()
            }

            override fun onScaleClick() {
                binding.textViewGuideB.text = getString(R.string.guide_success)
                SecondFragment.callback?.onScaleClick()
            }

            override fun onSuccess() {

            }

        }

        binding.textViewGuideB.text = getString(R.string.guide_move)
        binding.textViewMiddle.alpha = 1f
        SecondFragment.callback?.onSuccess()
    }

    companion object {
        var callback: FreeformStudyViewNew.Callback? = null
    }
}