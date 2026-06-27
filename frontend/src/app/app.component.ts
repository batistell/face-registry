import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

interface UserResponse {
  id: string;
  cpf: string;
  name: string;
  photoBase64: string;
  createdAt: string;
}

interface VerificationResponse {
  match: boolean;
  similarity: number;
  threshold: number;
}

interface IdentificationResponse {
  identified: boolean;
  cpf: string | null;
  name: string | null;
  photoBase64: string | null;
  similarity: number;
  threshold: number;
}

interface BatchItem {
  cpf: string;
  name: string;
  photoFile: File | null;
  previewUrl: string | null;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  private apiUrl = window.location.hostname === 'localhost' && window.location.port === '4200'
    ? 'http://localhost:8080/api/users'
    : 'api/users';

  // Navegação
  activeTab: 'list' | 'form' | 'batch' | 'verify' | 'identify' = 'list';
  isSidebarOpen = false;

  // Estado Geral
  users: UserResponse[] = [];
  selectedUser: UserResponse | null = null;
  isLoading = false;
  isActionLoading = false;
  successMsg = '';
  errorMsg = '';
  appVersion = 'v1.0.0-dev';

  // Filtro de Busca
  searchTerm = '';
  startDateFilter = '';
  endDateFilter = '';
  currentPage = 0;
  pageSize = 12;
  private searchTimeout: any;

  // Form de Cadastro/Edição Individual
  isEditMode = false;
  userForm = {
    cpf: '',
    name: '',
    photoFile: null as File | null,
    previewUrl: null as string | null
  };

  // Form de Cadastro em Lote (Batch)
  batchList: BatchItem[] = [];

  // Form de Verificação (1:1)
  verifyForm = {
    cpf: '',
    photoFile: null as File | null,
    previewUrl: null as string | null
  };
  verifyResult: VerificationResponse | null = null;

  // Form de Identificação (1:n)
  identifyForm = {
    photoFile: null as File | null,
    previewUrl: null as string | null
  };
  identifyResult: IdentificationResponse | null = null;

  // Webcam Capture
  isWebcamActive = false;
  webcamStream: MediaStream | null = null;
  webcamTarget: 'form' | 'verify' | 'identify' | number | null = null;
  webcamError = '';
  webcamZoom = 1.0;

  // Zoom & Pan State for Previews
  zoomState = {
    form: { zoom: 1, panX: 0, panY: 0, originalFile: null as File | null, originalUrl: null as string | null },
    verify: { zoom: 1, panX: 0, panY: 0, originalFile: null as File | null, originalUrl: null as string | null },
    identify: { zoom: 1, panX: 0, panY: 0, originalFile: null as File | null, originalUrl: null as string | null }
  };

  isDragging = false;
  dragStart = { x: 0, y: 0 };
  dragTarget: 'form' | 'verify' | 'identify' | null = null;

  constructor(private http: HttpClient) {}

  ngOnInit() {
    this.loadUsers();
    this.addBatchRow(); // Inicializa o lote com uma linha vazia
  }

  ngOnDestroy() {
    this.stopWebcam();
  }

  // --- Operações de API (Backend Integrado) ---

  loadUsers() {
    this.isLoading = true;
    this.errorMsg = '';

    let params: any = {
      page: this.currentPage.toString(),
      size: this.pageSize.toString()
    };
    if (this.searchTerm && this.searchTerm.trim()) {
      params.search = this.searchTerm.trim();
    }
    if (this.startDateFilter) {
      params.startDate = this.startDateFilter;
    }
    if (this.endDateFilter) {
      params.endDate = this.endDateFilter;
    }

    this.http.get<UserResponse[]>(this.apiUrl, { params }).subscribe({
      next: (data) => {
        this.users = data;
        this.isLoading = false;
      },
      error: (err) => {
        this.showError('Não foi possível conectar ao backend. Verifique se o servidor Spring Boot está rodando.');
        this.isLoading = false;
      }
    });
  }

  async saveUser() {
    if (!this.userForm.name.trim()) return this.showError('O nome é obrigatório.');
    if (!this.isEditMode) {
      if (!this.userForm.cpf.trim()) return this.showError('O CPF é obrigatório.');
      if (!this.isValidCpf(this.userForm.cpf)) return this.showError('CPF inválido. Insira um CPF válido com 11 dígitos.');
      this.userForm.cpf = this.userForm.cpf.replace(/\D/g, '');
    }
    if (!this.isEditMode && !this.userForm.photoFile) return this.showError('A foto é obrigatória para o cadastro.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.successMsg = '';

    const formData = new FormData();
    formData.append('name', this.userForm.name);

    if (this.userForm.photoFile) {
      try {
        const processedPhoto = await this.getProcessedFile('form');
        formData.append('photo', processedPhoto);
      } catch (e) {
        this.showError('Erro ao processar imagem para envio.');
        this.isActionLoading = false;
        return;
      }
    }

    if (this.isEditMode) {
      // Atualização
      this.http.put<UserResponse>(`${this.apiUrl}/${this.userForm.cpf}`, formData).subscribe({
        next: (res) => {
          this.showSuccess('Usuário atualizado com sucesso!');
          this.loadUsers();
          this.isActionLoading = false;
          this.changeTab('list');
        },
        error: (err) => {
          this.showError(err.error?.message || 'Erro ao atualizar usuário.');
          this.isActionLoading = false;
        }
      });
    } else {
      // Cadastro Novo
      formData.append('cpf', this.userForm.cpf);
      this.http.post<UserResponse>(this.apiUrl, formData).subscribe({
        next: (res) => {
          this.showSuccess('Usuário cadastrado com sucesso!');
          this.loadUsers();
          this.isActionLoading = false;
          this.changeTab('list');
        },
        error: (err) => {
          this.showError(err.error?.message || 'Erro ao cadastrar usuário.');
          this.isActionLoading = false;
        }
      });
    }
  }

  deleteUser(cpf: string, event: Event) {
    event.stopPropagation(); // Evita abrir detalhes do usuário ao clicar no botão
    if (!confirm('Tem certeza de que deseja excluir este usuário?')) return;

    this.isActionLoading = true;
    this.errorMsg = '';
    this.successMsg = '';

    this.http.delete(`${this.apiUrl}/${cpf}`).subscribe({
      next: () => {
        this.showSuccess('Usuário excluído com sucesso.');
        this.loadUsers();
        if (this.selectedUser?.cpf === cpf) {
          this.selectedUser = null;
        }
        this.isActionLoading = false;
      },
      error: (err) => {
        this.showError(err.error?.message || 'Erro ao excluir usuário.');
        this.isActionLoading = false;
      }
    });
  }

  sendBatch() {
    // Valida o lote
    if (this.batchList.length === 0) return this.showError('Adicione pelo menos 1 usuário no lote.');
    for (let i = 0; i < this.batchList.length; i++) {
      const item = this.batchList[i];
      if (!item.cpf || !this.isValidCpf(item.cpf)) return this.showError(`CPF inválido no item ${i + 1}. Insira um CPF válido com 11 dígitos.`);
      if (!item.name.trim()) return this.showError(`Nome vazio no item ${i + 1}.`);
      if (!item.photoFile) return this.showError(`A foto é obrigatória para todos os itens do lote (Item ${i + 1}).`);
      item.cpf = item.cpf.replace(/\D/g, '');
    }

    this.isActionLoading = true;
    this.errorMsg = '';
    this.successMsg = '';

    const formData = new FormData();
    this.batchList.forEach((item) => {
      formData.append('cpfs', item.cpf);
      formData.append('names', item.name);
      if (item.photoFile) {
        formData.append('photos', item.photoFile);
      }
    });

    this.http.post<UserResponse[]>(`${this.apiUrl}/batch`, formData).subscribe({
      next: (res) => {
        this.showSuccess(`Lote processado com sucesso! ${res.length} usuários cadastrados de forma concorrente.`);
        this.loadUsers();
        this.batchList = [];
        this.addBatchRow();
        this.isActionLoading = false;
        this.changeTab('list');
      },
      error: (err) => {
        this.showError(err.error?.message || 'Falha ao salvar o lote. Nenhuma alteração foi realizada.');
        this.isActionLoading = false;
      }
    });
  }

  async verifyFace() {
    if (!this.verifyForm.cpf.trim()) return this.showError('Insira o CPF do usuário a ser verificado.');
    if (!this.isValidCpf(this.verifyForm.cpf)) return this.showError('CPF do usuário alvo inválido. Insira um CPF válido com 11 dígitos.');
    this.verifyForm.cpf = this.verifyForm.cpf.replace(/\D/g, '');
    if (!this.verifyForm.photoFile) return this.showError('Faça o upload ou capture a foto para verificação.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.verifyResult = null;

    const formData = new FormData();
    formData.append('cpf', this.verifyForm.cpf);
    
    try {
      const processedPhoto = await this.getProcessedFile('verify');
      formData.append('photo', processedPhoto);
    } catch (e) {
      this.showError('Erro ao processar imagem para verificação.');
      this.isActionLoading = false;
      return;
    }

    this.http.post<VerificationResponse>(`${this.apiUrl}/verify`, formData).subscribe({
      next: (res) => {
        this.verifyResult = res;
        this.isActionLoading = false;
      },
      error: (err) => {
        this.showError(err.error?.message || 'Erro ao realizar verificação facial.');
        this.isActionLoading = false;
      }
    });
  }

  async identifyFace() {
    if (!this.identifyForm.photoFile) return this.showError('Faça o upload ou capture a foto para identificação.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.identifyResult = null;

    const formData = new FormData();
    
    try {
      const processedPhoto = await this.getProcessedFile('identify');
      formData.append('photo', processedPhoto);
    } catch (e) {
      this.showError('Erro ao processar imagem para identificação.');
      this.isActionLoading = false;
      return;
    }

    this.http.post<IdentificationResponse>(`${this.apiUrl}/identify`, formData).subscribe({
      next: (res) => {
        this.identifyResult = res;
        this.isActionLoading = false;
      },
      error: (err) => {
        this.showError(err.error?.message || 'Erro ao identificar rosto.');
        this.isActionLoading = false;
      }
    });
  }

  // --- Handlers de Upload de Arquivos ---

  onFileSelected(event: any, target: 'form' | 'verify' | 'identify' | number) {
    const file = event.target.files[0] as File;
    if (file) {
      const allowedExtensions = ['jpg', 'jpeg', 'png'];
      const fileExtension = file.name.split('.').pop()?.toLowerCase();
      if (!fileExtension || !allowedExtensions.includes(fileExtension)) {
        this.showError('Tipo de arquivo não permitido. Apenas imagens JPG, JPEG e PNG são aceitas.');
        event.target.value = '';
        return;
      }
      this.processSelectedFile(file, target);
    }
  }

  processSelectedFile(file: File, target: 'form' | 'verify' | 'identify' | number) {
    const reader = new FileReader();
    reader.onload = (e: any) => {
      const url = e.target.result;
      if (target === 'form') {
        this.userForm.photoFile = file;
        this.userForm.previewUrl = url;
        this.resetZoomState('form', file, url);
      } else if (target === 'verify') {
        this.verifyForm.photoFile = file;
        this.verifyForm.previewUrl = url;
        this.resetZoomState('verify', file, url);
      } else if (target === 'identify') {
        this.identifyForm.photoFile = file;
        this.identifyForm.previewUrl = url;
        this.resetZoomState('identify', file, url);
      } else if (typeof target === 'number') {
        this.batchList[target].photoFile = file;
        this.batchList[target].previewUrl = url;
      }
    };
    reader.readAsDataURL(file);
  }

  // --- Funções da Câmera (Webcam Nativa) ---

  startWebcam(target: 'form' | 'verify' | 'identify' | number) {
    this.webcamTarget = target;
    this.isWebcamActive = true;
    this.webcamError = '';
    this.webcamZoom = 1.0;

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      this.webcamError = 'Câmera indisponível. Para usar a câmera, acesse este site através de uma conexão segura (HTTPS).';
      return;
    }

    navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 } })
      .then((stream) => {
        this.webcamStream = stream;
        const video = document.querySelector('video#webcamVideo') as HTMLVideoElement;
        if (video) {
          video.srcObject = stream;
          video.play();
        }
      })
      .catch((err) => {
        this.webcamError = 'Não foi possível acessar a câmera: ' + err.message;
        logError(err);
      });
  }

  captureFrame() {
    const video = document.querySelector('video#webcamVideo') as HTMLVideoElement;
    if (!video) return;

    const canvas = document.createElement('canvas');
    const videoWidth = video.videoWidth;
    const videoHeight = video.videoHeight;
    const S = this.webcamZoom || 1.0;

    const subWidth = videoWidth / S;
    const subHeight = videoHeight / S;
    const sx = (videoWidth - subWidth) / 2;
    const sy = (videoHeight - subHeight) / 2;

    canvas.width = subWidth;
    canvas.height = subHeight;

    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.drawImage(video, sx, sy, subWidth, subHeight, 0, 0, subWidth, subHeight);
      const dataUrl = canvas.toDataURL('image/jpeg');

      // Converte dataUrl base64 para arquivo binário
      fetch(dataUrl)
        .then((res) => res.blob())
        .then((blob) => {
          const file = new File([blob], 'foto_captura.jpg', { type: 'image/jpeg' });
          if (this.webcamTarget !== null) {
            this.processSelectedFile(file, this.webcamTarget);
          }
          this.stopWebcam();
        });
    }
  }

  stopWebcam() {
    if (this.webcamStream) {
      this.webcamStream.getTracks().forEach((track) => track.stop());
      this.webcamStream = null;
    }
    this.isWebcamActive = false;
    this.webcamTarget = null;
    this.webcamZoom = 1.0;
  }

  toggleSidebar() {
    this.isSidebarOpen = !this.isSidebarOpen;
  }

  closeSidebar() {
    this.isSidebarOpen = false;
  }

  // --- Navegação e Gerenciamento de Lote ---

  changeTab(tab: 'list' | 'form' | 'batch' | 'verify' | 'identify') {
    this.activeTab = tab;
    this.isSidebarOpen = false;
    this.errorMsg = '';
    this.successMsg = '';
    this.stopWebcam();

    if (tab === 'form') {
      this.resetForm();
    } else if (tab === 'verify') {
      this.verifyForm = { cpf: '', photoFile: null, previewUrl: null };
      this.verifyResult = null;
    } else if (tab === 'identify') {
      this.identifyForm = { photoFile: null, previewUrl: null };
      this.identifyResult = null;
    }
  }

  editUser(user: UserResponse) {
    this.changeTab('form');
    this.isEditMode = true;
    const file = this.base64ToFile(user.photoBase64, 'foto_atual.jpg');
    const url = 'data:image/jpeg;base64,' + user.photoBase64;
    this.userForm = {
      cpf: user.cpf,
      name: user.name,
      photoFile: file,
      previewUrl: url
    };
    this.resetZoomState('form', file, url);
  }

  resetForm() {
    this.isEditMode = false;
    this.userForm = {
      cpf: '',
      name: '',
      photoFile: null,
      previewUrl: null
    };
  }

  selectUser(user: UserResponse) {
    this.selectedUser = user;
  }

  closeDetails() {
    this.selectedUser = null;
  }

  addBatchRow() {
    this.batchList.push({
      cpf: '',
      name: '',
      photoFile: null,
      previewUrl: null
    });
  }

  removeBatchRow(index: number) {
    this.batchList.splice(index, 1);
    if (this.batchList.length === 0) {
      this.addBatchRow();
    }
  }

  get filteredUsers() {
    return this.users;
  }

  onSearchChange() {
    this.currentPage = 0;
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout);
    }
    this.searchTimeout = setTimeout(() => {
      this.loadUsers();
    }, 400);
  }

  clearFilters() {
    this.searchTerm = '';
    this.startDateFilter = '';
    this.endDateFilter = '';
    this.currentPage = 0;
    this.loadUsers();
  }

  nextPage() {
    this.currentPage++;
    this.loadUsers();
  }

  prevPage() {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadUsers();
    }
  }

  // --- Helpers de Exibição ---

  showSuccess(msg: string) {
    this.successMsg = msg;
    this.errorMsg = '';
    setTimeout(() => this.successMsg = '', 6000);
  }

  showError(msg: string) {
    this.errorMsg = msg;
    this.successMsg = '';
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString('pt-BR') + ' ' + date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    } catch {
      return dateStr;
    }
  }

  isValidCpf(cpf: string): boolean {
    if (!cpf) return false;
    const cleanCpf = cpf.replace(/\D/g, '');
    if (cleanCpf.length !== 11) return false;
    if (/^(\d)\1{10}$/.test(cleanCpf)) return false;

    let sum = 0;
    for (let i = 0; i < 9; i++) {
      sum += parseInt(cleanCpf.charAt(i)) * (10 - i);
    }
    let rev = 11 - (sum % 11);
    if (rev === 10 || rev === 11) rev = 0;
    if (parseInt(cleanCpf.charAt(9)) !== rev) return false;

    sum = 0;
    for (let i = 0; i < 10; i++) {
      sum += parseInt(cleanCpf.charAt(i)) * (11 - i);
    }
    rev = 11 - (sum % 11);
    if (rev === 10 || rev === 11) rev = 0;
    return parseInt(cleanCpf.charAt(10)) === rev;
  }

  // --- Funções de Manipulação de Zoom e Pan ---

  resetZoomState(target: 'form' | 'verify' | 'identify', file: File, url: string) {
    this.zoomState[target] = {
      zoom: 1,
      panX: 0,
      panY: 0,
      originalFile: file,
      originalUrl: url
    };
  }

  clearZoomState(target: 'form' | 'verify' | 'identify') {
    this.zoomState[target] = {
      zoom: 1,
      panX: 0,
      panY: 0,
      originalFile: null,
      originalUrl: null
    };
  }

  onZoomChange(target: 'form' | 'verify' | 'identify') {
    if (this.zoomState[target].zoom === 1) {
      this.zoomState[target].panX = 0;
      this.zoomState[target].panY = 0;
    }
  }

  startDrag(event: MouseEvent | TouchEvent, target: 'form' | 'verify' | 'identify') {
    if (this.zoomState[target].zoom <= 1) return;
    event.preventDefault();
    this.isDragging = true;
    this.dragTarget = target;
    const clientX = event instanceof MouseEvent ? event.clientX : event.touches[0].clientX;
    const clientY = event instanceof MouseEvent ? event.clientY : event.touches[0].clientY;
    this.dragStart = {
      x: clientX - this.zoomState[target].panX,
      y: clientY - this.zoomState[target].panY
    };
  }

  onDrag(event: MouseEvent | TouchEvent) {
    if (!this.isDragging || !this.dragTarget) return;
    event.preventDefault();
    const clientX = event instanceof MouseEvent ? event.clientX : event.touches[0].clientX;
    const clientY = event instanceof MouseEvent ? event.clientY : event.touches[0].clientY;
    
    // Simple boundary clamping to keep image visible
    const zoom = this.zoomState[this.dragTarget].zoom;
    const maxPan = 150 * (zoom - 1);
    
    let newPanX = clientX - this.dragStart.x;
    let newPanY = clientY - this.dragStart.y;
    
    newPanX = Math.max(-maxPan, Math.min(maxPan, newPanX));
    newPanY = Math.max(-maxPan, Math.min(maxPan, newPanY));
    
    this.zoomState[this.dragTarget].panX = newPanX;
    this.zoomState[this.dragTarget].panY = newPanY;
  }

  endDrag() {
    this.isDragging = false;
    this.dragTarget = null;
  }

  base64ToFile(base64Str: string, filename: string): File {
    const byteString = atob(base64Str);
    const ab = new ArrayBuffer(byteString.length);
    const ia = new Uint8Array(ab);
    for (let i = 0; i < byteString.length; i++) {
      ia[i] = byteString.charCodeAt(i);
    }
    const blob = new Blob([ab], { type: 'image/jpeg' });
    return new File([blob], filename, { type: 'image/jpeg' });
  }

  getProcessedFile(target: 'form' | 'verify' | 'identify'): Promise<File> {
    const state = this.zoomState[target];
    const file = target === 'form' ? this.userForm.photoFile :
                 target === 'verify' ? this.verifyForm.photoFile :
                 this.identifyForm.photoFile;

    if (!file || !state.originalUrl || (state.zoom === 1 && state.panX === 0 && state.panY === 0)) {
      return Promise.resolve(file!);
    }

    return new Promise((resolve) => {
      const img = new Image();
      img.src = state.originalUrl!;
      img.onload = () => {
        const canvas = document.createElement('canvas');
        const W_i = img.naturalWidth;
        const H_i = img.naturalHeight;

        const S = state.zoom;
        const W_sub = W_i / S;
        const H_sub = H_i / S;

        const containerWidth = 400;
        const containerHeight = 300;

        const imgRatio = W_i / H_i;
        const containerRatio = containerWidth / containerHeight;

        let displayWidth = containerWidth;
        let displayHeight = containerHeight;

        if (imgRatio > containerRatio) {
          displayHeight = containerWidth / imgRatio;
        } else {
          displayWidth = containerHeight * imgRatio;
        }

        const scaleDisplay = displayWidth / W_i;

        let x_crop = (W_i / 2) * (1 - 1 / S) - state.panX / (scaleDisplay * S);
        let y_crop = (H_i / 2) * (1 - 1 / S) - state.panY / (scaleDisplay * S);

        x_crop = Math.max(0, Math.min(x_crop, W_i - W_sub));
        y_crop = Math.max(0, Math.min(y_crop, H_i - H_sub));

        canvas.width = W_sub;
        canvas.height = H_sub;

        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve(file);
          return;
        }

        ctx.drawImage(img, x_crop, y_crop, W_sub, H_sub, 0, 0, W_sub, H_sub);

        canvas.toBlob((blob) => {
          if (blob) {
            const croppedFile = new File([blob], file.name, { type: file.type });
            resolve(croppedFile);
          } else {
            resolve(file);
          }
        }, file.type, 0.95);
      };
      img.onerror = () => {
        resolve(file);
      };
    });
  }
}

function logError(err: any) {
  console.error(err);
}
