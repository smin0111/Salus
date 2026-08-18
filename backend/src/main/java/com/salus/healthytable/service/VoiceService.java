package com.salus.healthytable.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

@Service
public class VoiceService {

    // TODO: Integrate OpenAI Whisper API here
    // https://api.openai.com/v1/audio/transcriptions

    public Mono<String> transcribe(MultipartFile audioFile) {
        // Mock implementation for now
        // In real app: Send file to Whisper API and return text
        return Mono.just("음성 인식이 아직 연결되지 않았습니다. (Mock)");
    }
}
