import { createRouter, createWebHashHistory } from 'vue-router'
import HarborView from './views/HarborView.vue'
import MapView from './views/MapView.vue'
import SailView from './views/SailView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: HarborView },
    { path: '/map', component: MapView },
    { path: '/sail', component: SailView },
  ],
})