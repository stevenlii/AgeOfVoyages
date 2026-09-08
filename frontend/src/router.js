import { createRouter, createWebHashHistory } from 'vue-router'
import HarborView from './views/HarborView.vue'
import MapView from './views/MapView.vue'
import SailView from './views/SailView.vue'
import DockView from './views/DockView.vue'
import TownView from './views/TownView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: HarborView },
    { path: '/map', component: MapView },
    { path: '/sail', component: SailView },
    { path: '/dock', component: DockView },
    { path: '/town', component: TownView },
  ],
})