import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import RiRobot2Line from '~icons/ri/robot-2-line'
import MomoBirdView from './views/MomoBirdView.vue'

// "工具 → MomoBird": connection status, test, full sync and retry. Only super administrators and
// users granted plugin:momobird-ai:manage (or plugin management) see the entry, and the endpoints
// behind it refuse everyone else (see extensions/role-templates.yaml).
export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/momobird',
        name: 'MomoBirdAi',
        component: MomoBirdView,
        meta: {
          title: 'MomoBird',
          searchable: true,
          permissions: ['plugin:momobird-ai:manage'],
          menu: {
            name: 'MomoBird',
            group: 'tool',
            icon: markRaw(RiRobot2Line),
            priority: 20,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
