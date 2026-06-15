import { Platform } from 'react-native';
import { LOCAL_IP } from './secrets';

// 실 기기에서 테스트할 때는 'localhost'를 컴퓨터의 로컬 IP 주소(예: '192.168.0.x')로 변경하십시오.
// 터미널에서 'ipconfig getifaddr en0'(Mac) 또는 'ipconfig'(Windows) 명령어로 확인 가능합니다.
// const LOCAL_IP = '172.30.1.47'; // secrets.js 파일로 분리됨

const API_BASE_URL = Platform.select({
    ios: `http://${LOCAL_IP}:8080/api`,
    android: `http://${LOCAL_IP}:8080/api`, // 실 기기 연결 시 LOCAL_IP 사용
    web: `http://localhost:8080/api`,
    default: `http://${LOCAL_IP}:8080/api`,
});

export default {
    API_BASE_URL,
};
