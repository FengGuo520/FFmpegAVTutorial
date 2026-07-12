## 问题
1. std::move 移动语句的作用  右值引用的含义
2. 不过要注意一个细节：
   std::move 本身不移动，它只是把对象转换成“可以被移动”的右值引用。真正发生移动的是 AudioDecoderPcmBlock 的移动赋值运算符。
```c++
 pcmBlock->pcmData = std::move(pcmQueue.front().pcmData);
   pcmBlock->frameIndex = pcmQueue.front().frameIndex;
   pcmBlock->framePts = pcmQueue.front().framePts;
   pcmBlock->frameNbSamples = pcmQueue.front().frameNbSamples;
   pcmBlock->frameFormat = std::move(pcmQueue.front().frameFormat);
   pcmBlock->frameSampleRate = pcmQueue.front().frameSampleRate;
   pcmBlock->frameChannels = pcmQueue.front().frameChannels;
```

3.