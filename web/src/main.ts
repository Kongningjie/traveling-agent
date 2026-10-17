/** 前端唯一入口：挂载旅行工作台和全局样式，不初始化供应商客户端。 */
import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";

createApp(App).mount("#app");
