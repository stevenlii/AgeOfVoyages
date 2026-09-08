import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { init } from './game'

init()

createApp(App).use(router).mount('#app')