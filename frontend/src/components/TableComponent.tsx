import * as React from "react";
import { styled } from "@mui/material/styles";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell, { tableCellClasses } from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Paper from "@mui/material/Paper";
import { TablePagination } from "@mui/material";
import Checkbox from "@mui/material/Checkbox";
import Radio from "@mui/material/Radio";

interface TableProps {
  data: any[];
  columns: {
    header: string;
    id: string; // Key to access data
    align?: "right";
    check?: boolean; // true-Radio, false-Checkbox
    minWidth?: number;
    format?: (value: number) => string;
    // render?: (row: any) => React.JSX.Element; // Optional custom render function
  }[];
  onRowSelect?: (rowData: any) => void; // Callback when a row is selected
  //... other props
  actions?: (row: any) => React.JSX.Element; // A function returning action buttons/components for each row
}
const StyledTableCell = styled(TableCell)(({ theme }) => ({
  [`&.${tableCellClasses.head}`]: {
    backgroundColor: theme.palette.common.black,
    color: theme.palette.common.white,
  },
  [`&.${tableCellClasses.body}`]: {
    fontSize: 14,
  },
}));

const TableComponent: React.FC<TableProps> = ({
  data,
  columns,
  actions,
  onRowSelect,
}) => {
  const [page, setPage] = React.useState(0);
  const [rowsPerPage, setRowsPerPage] = React.useState(10);

  const handleChangePage = (_event: unknown, newPage: number) => {
    setPage(newPage);
  };

  const handleChangeRowsPerPage = (
    event: React.ChangeEvent<HTMLInputElement>
  ) => {
    setRowsPerPage(+event.target.value);
    setPage(0);
  };
  return (
    <Paper sx={{ width: "100%", overflow: "hidden" }}>
      <TableContainer component={Paper}>
        <Table stickyHeader size="small">
          <TableHead sx={{ background: "#263238" }}>
            <TableRow>
              {columns.some((col) => col.check) && (
                <TableCell padding="checkbox">
                  {/* This cell remains empty but provides necessary padding for alignment */}
                </TableCell>
              )}
              <TableCell padding="checkbox"></TableCell>
              {columns.map((col) => (
                <StyledTableCell
                  align={col.align}
                  key={col.id}
                  style={{ minWidth: col.minWidth }}
                >
                  {col.header}
                </StyledTableCell>
              ))}
              {actions && <TableCell>Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {data
              .slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)
              .map((row) => {
                return (
                  <TableRow
                    hover
                    role="checkbox"
                    tabIndex={-1}
                    key={row.code}
                    onClick={() => onRowSelect && onRowSelect(row)}
                  >
                    <TableCell padding="checkbox">
                      {columns.some((col) => col.check) ? (
                        columns[0].check ? ( // Assuming `check` property is consistent across columns
                          <Radio color="primary" size="small" />
                        ) : (
                          <Checkbox color="primary" size="small" />
                        )
                      ) : null}
                    </TableCell>
                    {columns.map((column) => {
                      const value = row[column.id];
                      return (
                        <StyledTableCell key={column.id} align={column.align}>
                          {column.format && typeof value === "number"
                            ? column.format(value)
                            : value}
                        </StyledTableCell>
                      );
                    })}
                  </TableRow>
                );
              })}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        rowsPerPageOptions={[10, 25, 100]}
        component="div"
        count={data.length}
        rowsPerPage={rowsPerPage}
        page={page}
        onPageChange={handleChangePage}
        onRowsPerPageChange={handleChangeRowsPerPage}
      />
    </Paper>
  );
};

export default TableComponent;
