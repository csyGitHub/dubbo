/*
 * Copyright 1999-2011 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;

import java.io.IOException;

/**
 * @author <a href="mailto:gang.lvg@taobao.com">kimi</a>
 */
//为什么要编解码（因为调用信息要通过网络传输到被调用方）
//可以理解为序列化和反序列，序列化是轻量级的持久化，网络传输时需要将转换为字节序列进行传输。
@SPI
public interface Codec2 {

    /**
     * 与Codec相比，就是把对字节输入输出流换位ChannelBuffer处理
     * 字节输入输出流是单向的，ChannelBuffer有游标readerIndex、writerIndex，可以双向处理
     */

    //编码：将POJO对象转换为byte数据在网络传输
    @Adaptive({Constants.CODEC_KEY}) //自适应扩展，会从url中获取到codec对应的值，然后选择不同编码器进行编码
    void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException;

    //解码：从网络中接收到byte数据并转换为对应的POJO对象
    @Adaptive({Constants.CODEC_KEY})
    Object decode(Channel channel, ChannelBuffer buffer) throws IOException;

    enum DecodeResult { //解码时，若没有收到完整的包，选择继续等待接收，还是忽视不管
        NEED_MORE_INPUT, SKIP_SOME_INPUT
    }

}

