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
    : '/api/users';

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

  // Filtro de Busca
  searchTerm = '';

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
    this.http.get<UserResponse[]>(this.apiUrl).subscribe({
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

  saveUser() {
    if (!this.userForm.name.trim()) return this.showError('O nome é obrigatório.');
    if (!this.isEditMode && !this.userForm.cpf.trim()) return this.showError('O CPF é obrigatório.');
    if (!this.isEditMode && this.userForm.cpf.length !== 11) return this.showError('O CPF deve conter exatamente 11 dígitos.');
    if (!this.isEditMode && !this.userForm.photoFile) return this.showError('A foto é obrigatória para o cadastro.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.successMsg = '';

    const formData = new FormData();
    formData.append('name', this.userForm.name);

    if (this.userForm.photoFile) {
      formData.append('photo', this.userForm.photoFile);
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
      if (!item.cpf || item.cpf.length !== 11) return this.showError(`CPF inválido no item ${i + 1}. Deve ter 11 dígitos.`);
      if (!item.name.trim()) return this.showError(`Nome vazio no item ${i + 1}.`);
      if (!item.photoFile) return this.showError(`A foto é obrigatória para todos os itens do lote (Item ${i + 1}).`);
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

  verifyFace() {
    if (!this.verifyForm.cpf.trim()) return this.showError('Insira o CPF do usuário a ser verificado.');
    if (!this.verifyForm.photoFile) return this.showError('Faça o upload ou capture a foto para verificação.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.verifyResult = null;

    const formData = new FormData();
    formData.append('cpf', this.verifyForm.cpf);
    formData.append('photo', this.verifyForm.photoFile);

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

  identifyFace() {
    if (!this.identifyForm.photoFile) return this.showError('Faça o upload ou capture a foto para identificação.');

    this.isActionLoading = true;
    this.errorMsg = '';
    this.identifyResult = null;

    const formData = new FormData();
    formData.append('photo', this.identifyForm.photoFile);

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
      } else if (target === 'verify') {
        this.verifyForm.photoFile = file;
        this.verifyForm.previewUrl = url;
      } else if (target === 'identify') {
        this.identifyForm.photoFile = file;
        this.identifyForm.previewUrl = url;
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
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;

    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
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
    this.userForm = {
      cpf: user.cpf,
      name: user.name,
      photoFile: null,
      previewUrl: 'data:image/jpeg;base64,' + user.photoBase64
    };
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
    if (!this.searchTerm.trim()) return this.users;
    const term = this.searchTerm.toLowerCase();
    return this.users.filter(u => 
      u.name.toLowerCase().includes(term) || u.cpf.includes(term)
    );
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
}

function logError(err: any) {
  console.error(err);
}
