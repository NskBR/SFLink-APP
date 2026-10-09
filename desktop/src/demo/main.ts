import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '../styles.css';
import { mount } from 'svelte';
import Demo from './Demo.svelte';
mount(Demo, { target: document.getElementById('app')! });
