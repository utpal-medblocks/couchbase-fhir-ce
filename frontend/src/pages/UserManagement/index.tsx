import { useEffect, useState } from "react";
import { createAuthClient } from "better-auth/react";
import { adminClient } from "better-auth/client/plugins";
import {
  Typography,
  Box,
  Table,
  TableContainer,
  TableHead,
  TableRow,
  TableCell,
  TableBody,
  TablePagination,
  Toolbar,
  InputLabel,
  MenuItem,
  TextField,
  Switch,
  FormControlLabel,
  Dialog,
  DialogTitle,
  Button,
  InputAdornment,
} from "@mui/material";
import Paper from "@mui/material/Paper";
import FormControl from "@mui/material/FormControl";
import Select, { SelectChangeEvent } from "@mui/material/Select";
import Alert from "@mui/material/Alert";
import CheckIcon from "@mui/icons-material/Check";
import {
  Error,
  PersonAdd,
  Email,
  AccountCircle,
  Key,
  BackHand,
} from "@mui/icons-material";

const authBaseURL = import.meta.env.VITE_AUTH_SERVER_BASE_URL
  ? import.meta.env.VITE_AUTH_SERVER_BASE_URL
  : "";

const authClient = createAuthClient({
  baseURL: authBaseURL,
  basePath: "/auth",
  plugins: [adminClient()],
});

type UserWithRole = {
  id: string;
  createdAt: Date;
  updatedAt: Date;
  email: string;
  name: string;
  role?: string;
};

const UserManagement = () => {
  const [users, setUsers] = useState<UserWithRole[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [searchField, setSearchField] = useState<"email" | "name" | "">(
    "email"
  );
  const [filterValue, setFilterValue] = useState<string>("");
  const [sortBy, setSortBy] = useState<string>("email");
  const [sortAscending, setSortAscending] = useState<boolean>(true);
  const [refetch, setRefetch] = useState<boolean>(true);
  const [showModal, setShowModal] = useState<boolean>(false);
  const [limit, setLimit] = useState<number>(10);

  useEffect(() => {
    (async () => {
      const offset = (page - 1) * limit;

      const result = await authClient.admin.listUsers({
        query: {
          offset,
          limit,
          searchField: searchField && filterValue ? searchField : undefined,
          filterValue: filterValue && searchField ? filterValue : undefined,
          filterOperator: searchField && filterValue ? "contains" : undefined,
          sortBy: sortBy ? sortBy : undefined,
          sortDirection: sortBy ? (sortAscending ? "asc" : "desc") : undefined,
        },
      });
      // users.data?.users[0].

      const pages = Math.ceil(result.data!.total / limit);
      if (page > pages) setPage(1);

      setUsers(result.data!.users as unknown as UserWithRole[]);
      setTotal(result.data!.total);
    })();
  }, [page, searchField, filterValue, sortBy, sortAscending, refetch, limit]);

  return (
    <Box component="section" sx={{ p: 2, mx: "2%", width: "70%" }}>
      <AddUserModal
        setRefetch={setRefetch}
        setShowModal={setShowModal}
        showModal={showModal}
      />
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          width: "100%",
          my: 2,
        }}
      >
        <Typography
          variant="h6"
          gutterBottom
          sx={{ mb: "1rem", display: "inline-block" }}
        >
          User Management
        </Typography>
        <Button variant="contained" onClick={() => setShowModal(true)}>
          <PersonAdd />
          <span style={{ marginLeft: 5 }}>Add User</span>
        </Button>
      </Box>
      <Box sx={{ width: "100%" }}>
        <Paper sx={{ width: "100%", mb: 2 }}>
          <Toolbar
            sx={{
              pl: { sm: 2 },
              pr: { xs: 1, sm: 1 },
              borderBottom: "1px solid #f1f1f1",
            }}
          >
            <FormControl variant="outlined" sx={{ m: 1, minWidth: 100 }}>
              <InputLabel id="user-management-input-label">
                Filter By
              </InputLabel>
              <Select
                value={searchField}
                label="Filter By"
                onChange={(e) => {
                  setSearchField(e.target.value);
                }}
              >
                <MenuItem value="">
                  <em>None</em>
                </MenuItem>
                <MenuItem value="email">Email</MenuItem>
                <MenuItem value="name">Name</MenuItem>
              </Select>
            </FormControl>
            <TextField
              id="filter-value-input"
              label="Name or Email"
              variant="outlined"
              value={filterValue}
              onChange={(e) => setFilterValue(e.target.value)}
              sx={{ flex: 1 }}
            />
            <FormControl variant="outlined" sx={{ m: 1, minWidth: 100 }}>
              <InputLabel id="user-management-input-label">Sort By</InputLabel>
              <Select
                value={sortBy}
                label="Sort By"
                onChange={(e) => {
                  setSortBy(e.target.value);
                }}
              >
                <MenuItem value="">
                  <em>None</em>
                </MenuItem>
                <MenuItem value="id">Id</MenuItem>
                <MenuItem value="email">Email</MenuItem>
                <MenuItem value="name">Name</MenuItem>
                <MenuItem value="role">role</MenuItem>
                <MenuItem value="updatedAt">Last Updated</MenuItem>
              </Select>
            </FormControl>
            <FormControlLabel
              control={
                <Switch
                  value={sortAscending}
                  defaultChecked
                  onChange={(e) => setSortAscending(e.target.checked)}
                />
              }
              label="Ascending"
            />
          </Toolbar>
          <TableContainer sx={{ width: "100%" }}>
            <Table size="medium" sx={{ width: "100%" }}>
              <TableHead>
                <TableRow>
                  <TableCell padding="normal">Id</TableCell>
                  <TableCell padding="normal">Email</TableCell>
                  <TableCell padding="normal">Name</TableCell>
                  <TableCell>Role</TableCell>
                  <TableCell padding="normal">Last Updated</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {users.map((user) => (
                  <TableRow key={user.id}>
                    <TableCell>{user.id}</TableCell>
                    <TableCell>{user.email}</TableCell>
                    <TableCell>{user.name}</TableCell>
                    <TableCell>{user.role}</TableCell>
                    <TableCell>
                      {user.updatedAt.toLocaleDateString()}{" "}
                      {user.updatedAt.toLocaleTimeString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            rowsPerPage={limit}
            rowsPerPageOptions={[1, 5, 10, 20]}
            onRowsPerPageChange={(e) => setLimit(parseInt(e.target.value))}
            count={total}
            page={page - 1}
            component="div"
            onPageChange={(e, page) => setPage(page + 1)}
          />
        </Paper>
      </Box>
    </Box>
  );
};

type AddUserModalProps = {
  setRefetch: React.Dispatch<React.SetStateAction<boolean>>;
  showModal: boolean;
  setShowModal: React.Dispatch<React.SetStateAction<boolean>>;
};

const AddUserModal = ({
  setRefetch,
  showModal,
  setShowModal,
}: AddUserModalProps) => {
  const [name, setName] = useState<string>("");
  const [email, setEmail] = useState<string>("");
  const [password, setPassword] = useState<string>("");
  const [role, setRole] = useState<string>("");
  const [successMessage, setSuccessMessage] = useState<string>("");
  const [errorMessages, setErrorMessages] = useState<string[]>([]);

  function isValidInput(): { valid: boolean; errorMessages: string[] } {
    const errorMessages: string[] = [];
    const emailRegex =
      /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}$/;

    if (!emailRegex.test(email))
      errorMessages.push(`'${email}' is not a valid email address`);
    if (!name) errorMessages.push(`Name cannnot be empty`);
    if (!password) errorMessages.push(`Password cannot be empty`);
    if (!role)
      errorMessages.push(`A role must be selected for creating a user`);

    return {
      valid: errorMessages.length === 0,
      errorMessages,
    };
  }

  function resetState() {
    setName("");
    setEmail("");
    setPassword("");
    setRole("");
  }

  async function handleAddUser(e: React.FormEvent<HTMLButtonElement>) {
    e.preventDefault();
    setErrorMessages([]);
    setSuccessMessage("");

    const { valid, errorMessages } = isValidInput();

    if (!valid) {
      setErrorMessages(errorMessages);
      return;
    }

    const result = await authClient.admin.createUser({
      name: name,
      email: email,
      password: password,
      role: role as "user" | "admin",
    });

    if ("error" in result && result.error) {
      setErrorMessages([
        `${result.error.code} ${result.error.statusText}: ${result.error.message}`,
      ]);
      return;
    }

    setSuccessMessage(
      `User with email: ${email} has been successfully created`
    );
    resetState();

    setTimeout(() => {
      setShowModal(false);
      setSuccessMessage("");
      setRefetch((val) => !val);
    }, 1200);
  }

  return (
    <Dialog
      onClose={() => {
        setShowModal(false);
        setErrorMessages([]);
        setSuccessMessage("");
      }}
      open={showModal}
      fullWidth
    >
      <DialogTitle sx={{ position: "relative" }}>
        Add New User{" "}
        <span style={{ position: "absolute", marginLeft: 5, top: 18 }}>
          <PersonAdd />
        </span>
      </DialogTitle>
      <Box sx={{ mx: 2, my: 2, gap: 2 }}>
        {successMessage && (
          <Alert icon={<CheckIcon fontSize="inherit" />} severity="success">
            {successMessage}
          </Alert>
        )}
        {errorMessages.length > 0 && (
          <Alert icon={<Error fontSize="inherit" />} severity="error">
            <ul style={{ color: "#992020" }}>
              {errorMessages.map((msg) => (
                <li style={{ color: "#992020" }}>{msg}</li>
              ))}
            </ul>
          </Alert>
        )}
        <TextField
          label="Name"
          variant="outlined"
          sx={{ width: "100%" }}
          margin="dense"
          value={name}
          onChange={(e) => setName(e.target.value)}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <AccountCircle />
                </InputAdornment>
              ),
            },
          }}
        />
        <TextField
          label="Email"
          variant="outlined"
          sx={{ width: "100%" }}
          margin="dense"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <Email />
                </InputAdornment>
              ),
            },
          }}
        />
        <TextField
          label="Password"
          variant="outlined"
          type="password"
          sx={{ width: "100%" }}
          margin="dense"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <Key />
                </InputAdornment>
              ),
            },
          }}
        />
        <FormControl variant="outlined" sx={{ width: "100%" }} margin="dense">
          <InputLabel id="user-management-input-label">Role</InputLabel>
          <Select
            value={role}
            label="Role"
            onChange={(e) => {
              setRole(e.target.value);
            }}
            startAdornment={
              <InputAdornment position="start">
                <BackHand />
              </InputAdornment>
            }
          >
            <MenuItem value="">
              <em>None</em>
            </MenuItem>
            <MenuItem value="admin">admin</MenuItem>
            <MenuItem value="user">member</MenuItem>
          </Select>
        </FormControl>
        <Button sx={{ my: 2 }} variant="contained" onClick={handleAddUser}>
          Add User
        </Button>
      </Box>
    </Dialog>
  );
};
export default UserManagement;
